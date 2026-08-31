from __future__ import annotations

import json
import logging
import os
import socket
from pathlib import Path
from typing import Any, Callable

import pika

from ..artifacts.store import ArtifactStore
from ..contracts.screening_events import ScreeningEvent
from ..engine import EngineResult, load_engine
from ..quality import AudioQuality, WavQualityAnalyzer


LOGGER = logging.getLogger("screening-worker")
EVENT_EXCHANGE = "alz.screening.events.x"
RETRY_EXCHANGE = "alz.screening.retry.x"
DEAD_LETTER_EXCHANGE = "alz.screening.dlx"
REQUEST_QUEUE = "alz.screening.transcription.q"
RETRY_QUEUE = "alz.screening.transcription.retry.10s.q"


class PermanentScreeningError(ValueError):
    """Input/configuration error that should not be retried."""


class ScreeningCancelled(Exception):
    """The current task attempt was cancelled and must not be retried."""


class CombinedScreeningWorker:
    def __init__(
        self,
        channel: pika.adapters.blocking_connection.BlockingChannel,
        audio_root: Path,
        artifact_store: ArtifactStore,
        quality_analyzer: WavQualityAnalyzer,
        engine: Any,
        max_retries: int,
    ) -> None:
        self._channel = channel
        self._audio_root = audio_root.resolve()
        self._artifact_store = artifact_store
        self._quality_analyzer = quality_analyzer
        self._engine = engine
        self._max_retries = max(0, max_retries)

    def consume(self) -> None:
        self._channel.basic_qos(prefetch_count=1)
        self._channel.basic_consume(
            queue=REQUEST_QUEUE, on_message_callback=self._on_message, auto_ack=False
        )
        LOGGER.info("Combined screening worker is waiting for tasks")
        self._channel.start_consuming()

    def _on_message(self, channel, method, properties, body: bytes) -> None:
        event: ScreeningEvent | None = None
        try:
            raw = json.loads(body.decode("utf-8"))
            event = ScreeningEvent.from_message(raw)
            if event.event_type != "screening.requested.v1":
                raise PermanentScreeningError("event routed to the wrong worker")
            self._process(event)
            channel.basic_ack(method.delivery_tag)
        except ScreeningCancelled:
            LOGGER.info(
                "Screening cancelled at checkpoint taskId=%s attempt=%s",
                event.task_id if event else "unknown",
                event.attempt if event else "unknown",
            )
            if event is not None:
                self._publish_cancelled(event)
                channel.basic_ack(method.delivery_tag)
            else:
                channel.basic_reject(method.delivery_tag, requeue=False)
        except (json.JSONDecodeError, UnicodeDecodeError, PermanentScreeningError, ValueError) as error:
            LOGGER.warning("Rejecting invalid screening event: %s", type(error).__name__)
            if event is not None:
                self._publish_failure(event, "INVALID_SCREENING_INPUT", "筛查输入无效，无法继续处理")
            channel.basic_reject(method.delivery_tag, requeue=False)
        except Exception as error:  # noqa: BLE001 - worker boundary must classify all failures
            LOGGER.exception(
                "Screening stage failed taskId=%s attempt=%s",
                event.task_id if event else "unknown",
                event.attempt if event else "unknown",
            )
            if event is None:
                channel.basic_reject(method.delivery_tag, requeue=False)
            elif event.delivery_attempt < self._max_retries:
                self._publish(
                    "screening.stage.retrying.v1",
                    event.next_event(
                        "screening.stage.retrying.v1",
                        f"retrying-{event.attempt}-{event.delivery_attempt + 1}",
                        {
                            "stage": "PROCESSING",
                            "progress": 20,
                            "errorCode": type(error).__name__,
                        },
                    ),
                )
                self._publish(
                    "screening.requested.v1", event.retry_message(), exchange=RETRY_EXCHANGE
                )
                channel.basic_ack(method.delivery_tag)
            else:
                self._publish_failure(
                    event, "SCREENING_RETRY_EXHAUSTED", "后台处理多次失败，请稍后重试"
                )
                channel.basic_reject(method.delivery_tag, requeue=False)

    def _process(self, event: ScreeningEvent) -> None:
        self._raise_if_cancelled(event)
        existing = self._artifact_store.existing_analysis(event.task_id)
        current_model = str(getattr(self._engine, "model_version", "unknown"))
        if (existing is not None
                and self._artifact_store.existing_analysis_model(event.task_id) == "quality-only-v1"
                and current_model != "quality-only-v1"):
            LOGGER.warning(
                "Discarding stale quality-only artifact taskId=%s currentModel=%s",
                event.task_id,
                current_model,
            )
            self._artifact_store.discard_analysis(event.task_id)
            existing = None
        if existing is not None:
            self._raise_if_cancelled(event)
            self._publish_completed(event, *existing)
            return

        audio_path = self._resolve_audio(event)
        self._raise_if_cancelled(event)
        self._publish_stage(event, "screening.transcription.started.v1", "transcription-started")
        quality = self._quality_analyzer.analyze(audio_path)
        self._raise_if_cancelled(event)

        staged = getattr(self._engine, "analyze_staged", None)
        if callable(staged):
            result = staged(
                audio_path,
                quality,
                lambda stage: self._stage_callback(event, stage),
                lambda: self._raise_if_cancelled(event),
            )
        else:
            self._raise_if_cancelled(event)
            result = self._engine.analyze(audio_path, quality)
            self._raise_if_cancelled(event)

        self._raise_if_cancelled(event)
        artifact = {
            "transcription": result.transcription,
            "report": result.report,
            "risk_level": result.risk_level,
            "risk_score": result.risk_score,
            "quality_passed": quality.passed,
            "quality_issues": list(quality.issues),
            "quality_metrics": quality.metrics(),
            "feature_highlights": list(result.feature_highlights),
            "model_version": result.model_version,
        }
        uri, sha256 = self._artifact_store.write_analysis(event.task_id, artifact)
        self._raise_if_cancelled(event)
        self._publish_completed(event, uri, sha256)

    def _resolve_audio(self, event: ScreeningEvent) -> Path:
        audio_name = str(event.payload.get("audioName") or "")
        if not audio_name or Path(audio_name).name != audio_name:
            raise PermanentScreeningError("invalid audioName")
        path = (self._audio_root / audio_name).resolve()
        try:
            path.relative_to(self._audio_root)
        except ValueError as error:
            raise PermanentScreeningError("audio path escapes AUDIO_ROOT") from error
        if not path.is_file():
            raise PermanentScreeningError("audio file does not exist")
        return path

    def _stage_callback(self, event: ScreeningEvent, stage: str) -> None:
        self._raise_if_cancelled(event)
        mapping = {
            "TRANSCRIPTION": ("screening.transcription.started.v1", "transcription-started"),
            "FEATURES": ("screening.features.started.v1", "features-started"),
            "LLM": ("screening.llm.started.v1", "llm-started"),
        }
        item = mapping.get(stage)
        if item:
            self._publish_stage(event, *item)

    def _raise_if_cancelled(self, event: ScreeningEvent) -> None:
        if self._artifact_store.is_cancellation_requested(event.task_id, event.attempt):
            raise ScreeningCancelled()

    def _publish_stage(self, event: ScreeningEvent, event_type: str, stage: str) -> None:
        self._publish(event_type, event.next_event(event_type, stage))

    def _publish_completed(self, event: ScreeningEvent, uri: str, sha256: str) -> None:
        event_type = "screening.analysis.completed.v1"
        completed = event.next_event(
            event_type,
            "analysis-completed",
            {"artifactUri": uri, "sha256": sha256},
        )
        self._publish(event_type, completed)

    def _publish_failure(self, event: ScreeningEvent, code: str, message: str) -> None:
        event_type = "screening.stage.failed.v1"
        failed = event.next_event(
            event_type,
            f"failed-{event.attempt}",
            {"stage": "PROCESSING", "errorCode": code, "message": message},
        )
        self._publish(event_type, failed)

    def _publish_cancelled(self, event: ScreeningEvent) -> None:
        event_type = "screening.cancelled.v1"
        cancelled = event.next_event(
            event_type,
            f"cancelled-{event.attempt}",
            {"stage": "CANCELLED", "progress": 0},
        )
        self._publish(event_type, cancelled)

    def _publish(
        self, routing_key: str, event: dict[str, Any], exchange: str = EVENT_EXCHANGE
    ) -> None:
        published = self._channel.basic_publish(
            exchange=exchange,
            routing_key=routing_key,
            body=json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=pika.DeliveryMode.Persistent,
                correlation_id=str(event["taskId"]),
                message_id=str(event["eventId"]),
                headers={"traceId": str(event["traceId"])},
            ),
            mandatory=True,
        )
        if published is False:
            raise RuntimeError("RabbitMQ did not confirm event publication")


def _connection_parameters() -> pika.ConnectionParameters:
    credentials = pika.PlainCredentials(
        os.getenv("RABBITMQ_USERNAME", "alz_app"),
        os.getenv("RABBITMQ_PASSWORD", ""),
    )
    return pika.ConnectionParameters(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", "5672")),
        virtual_host=os.getenv("RABBITMQ_VHOST", "/alz"),
        credentials=credentials,
        heartbeat=60,
        blocked_connection_timeout=30,
        connection_attempts=10,
        retry_delay=5,
        client_properties={"connection_name": f"screening-worker-{socket.gethostname()}"},
    )


def _declare_topology(channel) -> None:
    channel.exchange_declare(EVENT_EXCHANGE, "topic", durable=True)
    channel.exchange_declare(RETRY_EXCHANGE, "topic", durable=True)
    channel.exchange_declare(DEAD_LETTER_EXCHANGE, "topic", durable=True)
    channel.queue_declare(
        REQUEST_QUEUE,
        durable=True,
        arguments={
            "x-dead-letter-exchange": DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key": f"{REQUEST_QUEUE}.dead",
        },
    )
    channel.queue_bind(REQUEST_QUEUE, EVENT_EXCHANGE, "screening.requested.v1")
    channel.queue_declare(
        RETRY_QUEUE,
        durable=True,
        arguments={
            "x-message-ttl": int(os.getenv("SCREENING_RETRY_DELAY_MS", "10000")),
            "x-dead-letter-exchange": EVENT_EXCHANGE,
            "x-dead-letter-routing-key": "screening.requested.v1",
        },
    )
    channel.queue_bind(RETRY_QUEUE, RETRY_EXCHANGE, "screening.requested.v1")


def main() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    default_root = Path(__file__).resolve().parents[3]
    audio_root = Path(os.getenv("AUDIO_ROOT", default_root / "data" / "audio")).resolve()
    artifact_root = Path(
        os.getenv("SCREENING_ARTIFACT_DIR", default_root / "data" / "screening-artifacts")
    ).resolve()

    engine = load_engine()
    LOGGER.info(
        "Screening engine initialized engine=%s model=%s deepseekConfigured=%s "
        "whisperDevice=%s whisperDeviceIndex=%s whisperComputeType=%s workerId=%s",
        engine.__class__.__name__,
        getattr(engine, "model_version", "unknown"),
        bool(os.getenv("DEEPSEEK_API_KEY", "").strip()),
        os.getenv("WHISPER_DEVICE", "cpu"),
        os.getenv("WHISPER_DEVICE_INDEX", "0"),
        os.getenv("WHISPER_COMPUTE_TYPE", "int8"),
        os.getenv("SCREENING_WORKER_ID", "unknown"),
    )

    connection = pika.BlockingConnection(_connection_parameters())
    channel = connection.channel()
    channel.confirm_delivery()
    _declare_topology(channel)
    worker = CombinedScreeningWorker(
        channel=channel,
        audio_root=audio_root,
        artifact_store=ArtifactStore(artifact_root),
        quality_analyzer=WavQualityAnalyzer(),
        engine=engine,
        max_retries=int(os.getenv("SCREENING_WORKER_MAX_RETRIES", "3")),
    )
    try:
        worker.consume()
    finally:
        if connection.is_open:
            connection.close()


if __name__ == "__main__":
    main()
