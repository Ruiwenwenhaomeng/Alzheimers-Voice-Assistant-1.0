from __future__ import annotations

import argparse
import json
import logging
import os
import socket
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import pika

from ..artifacts.store import ArtifactStore
from ..contracts.screening_events import ScreeningEvent
from ..quality import WavQualityAnalyzer


LOGGER = logging.getLogger("screening-pipeline-worker")
EVENT_EXCHANGE = "alz.screening.events.x"
RETRY_EXCHANGE = "alz.screening.retry.x"
DEAD_LETTER_EXCHANGE = "alz.screening.dlx"


@dataclass(frozen=True)
class StageDefinition:
    name: str
    input_event: str
    queue: str
    retry_queue: str
    failure_stage: str
    retry_progress: int


STAGES = {
    "transcription": StageDefinition(
        "transcription",
        "screening.requested.v1",
        "alz.screening.transcription.q",
        "alz.screening.transcription.retry.10s.q",
        "TRANSCRIPTION",
        20,
    ),
    "features": StageDefinition(
        "features",
        "screening.transcription.completed.v1",
        "alz.screening.features.q",
        "alz.screening.features.retry.10s.q",
        "FEATURES",
        50,
    ),
    "llm": StageDefinition(
        "llm",
        "screening.features.completed.v1",
        "alz.screening.llm.q",
        "alz.screening.llm.retry.10s.q",
        "LLM",
        70,
    ),
}


class PermanentScreeningError(ValueError):
    """Invalid input that must go directly to the dead-letter path."""


class ScreeningCancelled(Exception):
    """The current task attempt was cancelled at a safe checkpoint."""


class PipelineWorker:
    def __init__(
        self,
        stage: StageDefinition,
        channel,
        audio_root: Path,
        artifact_store: ArtifactStore,
        max_retries: int,
        quality_analyzer: Any | None = None,
        transcribe: Callable[..., str] | None = None,
        extract_mfcc: Callable[[Path], list[float]] | None = None,
        extract_semantic: Callable[[str], dict[str, float]] | None = None,
        call_llm: Callable[[str, list[float], dict[str, float]], str] | None = None,
    ) -> None:
        self._stage = stage
        self._channel = channel
        self._audio_root = audio_root.resolve()
        self._artifact_store = artifact_store
        self._max_retries = max(0, max_retries)
        self._quality_analyzer = quality_analyzer
        self._transcribe = transcribe
        self._extract_mfcc = extract_mfcc
        self._extract_semantic = extract_semantic
        self._call_llm = call_llm

    def consume(self) -> None:
        self._channel.basic_qos(prefetch_count=1)
        self._channel.basic_consume(
            queue=self._stage.queue,
            on_message_callback=self._on_message,
            auto_ack=False,
        )
        LOGGER.info("%s worker is waiting for tasks", self._stage.name)
        self._channel.start_consuming()

    def _on_message(self, channel, method, properties, body: bytes) -> None:
        event: ScreeningEvent | None = None
        started_at = time.monotonic()
        try:
            event = ScreeningEvent.from_message(json.loads(body.decode("utf-8")))
            if event.event_type != self._stage.input_event:
                raise PermanentScreeningError("event routed to the wrong stage")
            self._process(event)
            channel.basic_ack(method.delivery_tag)
            LOGGER.info(
                "Stage completed stage=%s taskId=%s attempt=%s durationMs=%d",
                self._stage.name,
                event.task_id,
                event.attempt,
                int((time.monotonic() - started_at) * 1000),
            )
        except ScreeningCancelled:
            LOGGER.info(
                "Task cancelled stage=%s taskId=%s attempt=%s",
                self._stage.name,
                event.task_id if event else "unknown",
                event.attempt if event else "unknown",
            )
            if event is not None:
                self._publish_cancelled(event)
                channel.basic_ack(method.delivery_tag)
            else:
                channel.basic_reject(method.delivery_tag, requeue=False)
        except (json.JSONDecodeError, UnicodeDecodeError, PermanentScreeningError, ValueError) as error:
            LOGGER.warning("Rejecting invalid %s event: %s", self._stage.name, type(error).__name__)
            if event is not None:
                self._publish_failure(event, "INVALID_SCREENING_INPUT", "筛查输入无效，无法继续处理")
            channel.basic_reject(method.delivery_tag, requeue=False)
        except Exception as error:  # noqa: BLE001 - worker boundary classifies failures
            LOGGER.exception(
                "Stage failed stage=%s taskId=%s attempt=%s deliveryAttempt=%s",
                self._stage.name,
                event.task_id if event else "unknown",
                event.attempt if event else "unknown",
                event.delivery_attempt if event else "unknown",
            )
            if event is None:
                channel.basic_reject(method.delivery_tag, requeue=False)
            elif event.delivery_attempt < self._max_retries:
                retry_number = event.delivery_attempt + 1
                self._publish(
                    "screening.stage.retrying.v1",
                    event.next_event(
                        "screening.stage.retrying.v1",
                        f"{self._stage.name}-retrying-{event.attempt}-{retry_number}",
                        {
                            "stage": self._stage.failure_stage,
                            "progress": self._stage.retry_progress,
                            "errorCode": type(error).__name__,
                            "deliveryAttempt": retry_number,
                        },
                    ),
                )
                self._publish(
                    self._stage.input_event,
                    event.retry_message(),
                    exchange=RETRY_EXCHANGE,
                )
                channel.basic_ack(method.delivery_tag)
            else:
                self._publish_failure(
                    event,
                    "SCREENING_RETRY_EXHAUSTED",
                    f"{self._stage.failure_stage} 阶段多次失败，请稍后重新筛查",
                )
                channel.basic_reject(method.delivery_tag, requeue=False)

    def _process(self, event: ScreeningEvent) -> None:
        self._raise_if_cancelled(event)
        if self._stage.name == "transcription":
            self._process_transcription(event)
        elif self._stage.name == "features":
            self._process_features(event)
        elif self._stage.name == "llm":
            self._process_llm(event)
        else:  # pragma: no cover - definitions are validated by argparse
            raise PermanentScreeningError("unknown screening stage")

    def _process_transcription(self, event: ScreeningEvent) -> None:
        filename = self._attempt_filename("transcription", event)
        existing = self._artifact_store.existing_document(event.task_id, filename)
        audio_name = self._required_text(event.payload, "audioName")
        if existing is not None:
            self._raise_if_cancelled(event)
            self._publish_stage_completed(
                event, "screening.transcription.completed.v1", "transcription-completed",
                audio_name, *existing
            )
            return

        audio_path = self._resolve_audio(audio_name)
        self._publish_stage(event, "screening.transcription.started.v1", "transcription-started")
        self._raise_if_cancelled(event)
        if self._quality_analyzer is None or self._transcribe is None:
            raise RuntimeError("transcription worker dependencies are not initialized")
        quality = self._quality_analyzer.analyze(audio_path)
        self._raise_if_cancelled(event)
        transcription = self._transcribe(audio_path, lambda: self._raise_if_cancelled(event))
        self._raise_if_cancelled(event)
        uri, sha256 = self._artifact_store.write_document(
            event.task_id,
            filename,
            {
                "_attempt": event.attempt,
                "transcription": transcription or "[未识别到有效语音]",
                "quality_passed": quality.passed,
                "quality_issues": list(quality.issues),
                "quality_metrics": quality.metrics(),
            },
        )
        self._raise_if_cancelled(event)
        self._publish_stage_completed(
            event, "screening.transcription.completed.v1", "transcription-completed",
            audio_name, uri, sha256
        )

    def _process_features(self, event: ScreeningEvent) -> None:
        filename = self._attempt_filename("features", event)
        existing = self._artifact_store.existing_document(event.task_id, filename)
        audio_name = self._required_text(event.payload, "audioName")
        if existing is not None:
            self._raise_if_cancelled(event)
            self._publish_stage_completed(
                event, "screening.features.completed.v1", "features-completed",
                audio_name, *existing
            )
            return

        transcription = self._artifact_store.read_document(
            event.task_id,
            self._attempt_filename("transcription", event),
            self._required_text(event.payload, "artifactUri"),
            self._required_text(event.payload, "sha256"),
        )
        self._raise_if_cancelled(event)
        self._publish_stage(event, "screening.features.started.v1", "features-started")
        if self._extract_mfcc is None or self._extract_semantic is None:
            raise RuntimeError("feature worker dependencies are not initialized")
        text = self._required_text(transcription, "transcription")
        mfcc = self._extract_mfcc(self._resolve_audio(audio_name))
        self._raise_if_cancelled(event)
        semantic = self._extract_semantic(text)
        self._raise_if_cancelled(event)
        uri, sha256 = self._artifact_store.write_document(
            event.task_id,
            filename,
            {
                **transcription,
                "mfcc_features": mfcc,
                "semantic_features": semantic,
            },
        )
        self._raise_if_cancelled(event)
        self._publish_stage_completed(
            event, "screening.features.completed.v1", "features-completed",
            audio_name, uri, sha256
        )

    def _process_llm(self, event: ScreeningEvent) -> None:
        existing = self._artifact_store.existing_analysis(event.task_id)
        if existing is not None:
            payload = self._artifact_store.read_document(
                event.task_id, "analysis.json", existing[0], existing[1]
            )
            if int(payload.get("_attempt", -1)) == event.attempt:
                self._raise_if_cancelled(event)
                self._publish_completed(event, *existing)
                return
            self._artifact_store.discard_analysis(event.task_id)

        features = self._artifact_store.read_document(
            event.task_id,
            self._attempt_filename("features", event),
            self._required_text(event.payload, "artifactUri"),
            self._required_text(event.payload, "sha256"),
        )
        self._raise_if_cancelled(event)
        self._publish_stage(event, "screening.llm.started.v1", "llm-started")
        text = self._required_text(features, "transcription")
        mfcc = self._number_list(features.get("mfcc_features"), "mfcc_features")
        semantic = self._number_map(features.get("semantic_features"), "semantic_features")
        if self._call_llm is None:
            raise RuntimeError("LLM worker dependency is not initialized")
        report = self._call_llm(text, mfcc, semantic)
        self._raise_if_cancelled(event)
        highlights = ["已完成 Whisper 中文转写", "已提取 MFCC 声学特征"]
        if "ttr" in semantic:
            highlights.append(f"词汇多样性 TTR={semantic['ttr']:.2f}")
        if "avg_sentence_length" in semantic:
            highlights.append(f"平均句长={semantic['avg_sentence_length']:.2f}")
        quality_issues = [str(item) for item in features.get("quality_issues", [])]
        if not bool(features.get("quality_passed", False)):
            highlights.extend(quality_issues)
        artifact = {
            "_attempt": event.attempt,
            "transcription": text,
            "report": report or "语音筛查报告生成失败，请重新采集或稍后再试。",
            "risk_level": "INCONCLUSIVE",
            "risk_score": None,
            "quality_passed": bool(features.get("quality_passed", False)),
            "quality_issues": quality_issues,
            "quality_metrics": dict(features.get("quality_metrics") or {}),
            "feature_highlights": highlights,
            "model_version": os.getenv(
                "SCREENING_PIPELINE_MODEL_VERSION", "deepseek-whisper-mfcc-v1"
            ),
        }
        uri, sha256 = self._artifact_store.write_analysis(event.task_id, artifact)
        self._raise_if_cancelled(event)
        self._publish_completed(event, uri, sha256)

    def _publish_stage_completed(
        self,
        event: ScreeningEvent,
        event_type: str,
        event_suffix: str,
        audio_name: str,
        uri: str,
        sha256: str,
    ) -> None:
        self._publish(
            event_type,
            event.next_event(
                event_type,
                event_suffix,
                {"audioName": audio_name, "artifactUri": uri, "sha256": sha256},
            ),
        )

    def _publish_completed(self, event: ScreeningEvent, uri: str, sha256: str) -> None:
        event_type = "screening.analysis.completed.v1"
        self._publish(
            event_type,
            event.next_event(
                event_type,
                "analysis-completed",
                {"artifactUri": uri, "sha256": sha256},
            ),
        )

    def _publish_stage(self, event: ScreeningEvent, event_type: str, suffix: str) -> None:
        self._publish(event_type, event.next_event(event_type, suffix))

    def _publish_failure(self, event: ScreeningEvent, code: str, message: str) -> None:
        event_type = "screening.stage.failed.v1"
        self._publish(
            event_type,
            event.next_event(
                event_type,
                f"{self._stage.name}-failed-{event.attempt}-{event.delivery_attempt}",
                {
                    "stage": self._stage.failure_stage,
                    "errorCode": code,
                    "message": message,
                },
            ),
        )

    def _publish_cancelled(self, event: ScreeningEvent) -> None:
        event_type = "screening.cancelled.v1"
        self._publish(
            event_type,
            event.next_event(
                event_type,
                f"{self._stage.name}-cancelled-{event.attempt}",
                {"stage": "CANCELLED", "progress": 0},
            ),
        )

    def _publish(self, routing_key: str, event: dict[str, Any], exchange: str = EVENT_EXCHANGE) -> None:
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

    def _raise_if_cancelled(self, event: ScreeningEvent) -> None:
        if self._artifact_store.is_cancellation_requested(event.task_id, event.attempt):
            raise ScreeningCancelled()

    def _resolve_audio(self, audio_name: str) -> Path:
        if Path(audio_name).name != audio_name:
            raise PermanentScreeningError("invalid audioName")
        path = (self._audio_root / audio_name).resolve()
        try:
            path.relative_to(self._audio_root)
        except ValueError as error:
            raise PermanentScreeningError("audio path escapes AUDIO_ROOT") from error
        if not path.is_file():
            raise PermanentScreeningError("audio file does not exist")
        return path

    @staticmethod
    def _attempt_filename(stage: str, event: ScreeningEvent) -> str:
        return f"{stage}-attempt-{event.attempt}.json"

    @staticmethod
    def _required_text(payload: dict[str, Any], key: str) -> str:
        value = payload.get(key)
        if value is None or not str(value).strip():
            raise PermanentScreeningError(f"missing {key}")
        return str(value)

    @staticmethod
    def _number_list(value: Any, name: str) -> list[float]:
        if not isinstance(value, list):
            raise PermanentScreeningError(f"invalid {name}")
        try:
            return [float(item) for item in value]
        except (TypeError, ValueError) as error:
            raise PermanentScreeningError(f"invalid {name}") from error

    @staticmethod
    def _number_map(value: Any, name: str) -> dict[str, float]:
        if not isinstance(value, dict):
            raise PermanentScreeningError(f"invalid {name}")
        try:
            return {str(key): float(item) for key, item in value.items()}
        except (TypeError, ValueError) as error:
            raise PermanentScreeningError(f"invalid {name}") from error


def _connection_parameters() -> pika.ConnectionParameters:
    return pika.ConnectionParameters(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", "5672")),
        virtual_host=os.getenv("RABBITMQ_VHOST", "/alz"),
        credentials=pika.PlainCredentials(
            os.getenv("RABBITMQ_USERNAME", "alz_app"),
            os.getenv("RABBITMQ_PASSWORD", ""),
        ),
        heartbeat=60,
        blocked_connection_timeout=30,
        connection_attempts=10,
        retry_delay=5,
        client_properties={"connection_name": f"screening-pipeline-{socket.gethostname()}"},
    )


def _durable_stage_queue(channel, queue: str) -> None:
    channel.queue_declare(
        queue,
        durable=True,
        arguments={
            "x-dead-letter-exchange": DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key": f"{queue}.dead",
        },
    )


def _declare_topology(channel) -> None:
    channel.exchange_declare(EVENT_EXCHANGE, "topic", durable=True)
    channel.exchange_declare(RETRY_EXCHANGE, "topic", durable=True)
    channel.exchange_declare(DEAD_LETTER_EXCHANGE, "topic", durable=True)
    retry_delay = int(os.getenv("SCREENING_RETRY_DELAY_MS", "10000"))
    for stage in STAGES.values():
        _durable_stage_queue(channel, stage.queue)
        channel.queue_bind(stage.queue, EVENT_EXCHANGE, stage.input_event)
        channel.queue_declare(
            stage.retry_queue,
            durable=True,
            arguments={
                "x-message-ttl": retry_delay,
                "x-dead-letter-exchange": EVENT_EXCHANGE,
                "x-dead-letter-routing-key": stage.input_event,
            },
        )
        channel.queue_bind(stage.retry_queue, RETRY_EXCHANGE, stage.input_event)


def _dependencies(stage: str) -> dict[str, Any]:
    if stage == "transcription":
        from ..ad_diagnose import get_whisper_model, transcribe_audio

        get_whisper_model()  # Keep the model resident before the worker accepts its first task.
        return {"quality_analyzer": WavQualityAnalyzer(), "transcribe": transcribe_audio}
    if stage == "features":
        from ..ad_diagnose import extract_mfcc, extract_semantic_features

        extract_semantic_features("流水线预热")
        return {"extract_mfcc": extract_mfcc, "extract_semantic": extract_semantic_features}
    from ..ad_diagnose import call_deepseek_api

    has_api_key = bool(os.getenv("DEEPSEEK_API_KEY", "").strip())
    if os.getenv("SCREENING_REQUIRE_AI", "false").lower() == "true" and not has_api_key:
        raise RuntimeError("SCREENING_REQUIRE_AI=true but DEEPSEEK_API_KEY is missing")
    if not has_api_key:
        os.environ["SCREENING_PIPELINE_MODEL_VERSION"] = "quality-only-v1"

        def quality_only_report(_text, _mfcc, _semantic):
            return (
                "当前未配置大模型，本次只完成录音质量、转写和特征处理，"
                "不能据此判断认知风险。"
            )

        return {"call_llm": quality_only_report}
    return {"call_llm": call_deepseek_api}


def main() -> None:
    parser = argparse.ArgumentParser(description="Run one screening pipeline stage")
    parser.add_argument("stage", choices=tuple(STAGES))
    args = parser.parse_args()
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    default_root = Path(__file__).resolve().parents[3]
    audio_root = Path(os.getenv("AUDIO_ROOT", default_root / "data" / "audio")).resolve()
    artifact_root = Path(
        os.getenv("SCREENING_ARTIFACT_DIR", default_root / "data" / "screening-artifacts")
    ).resolve()
    dependencies = _dependencies(args.stage)
    connection = pika.BlockingConnection(_connection_parameters())
    channel = connection.channel()
    channel.confirm_delivery()
    _declare_topology(channel)
    worker = PipelineWorker(
        stage=STAGES[args.stage],
        channel=channel,
        audio_root=audio_root,
        artifact_store=ArtifactStore(artifact_root),
        max_retries=int(os.getenv("SCREENING_WORKER_MAX_RETRIES", "3")),
        **dependencies,
    )
    LOGGER.info(
        "Pipeline worker initialized stage=%s workerId=%s device=%s:%s",
        args.stage,
        os.getenv("SCREENING_WORKER_ID", "unknown"),
        os.getenv("WHISPER_DEVICE", "cpu") if args.stage == "transcription" else "not-used",
        os.getenv("WHISPER_DEVICE_INDEX", "0") if args.stage == "transcription" else "not-used",
    )
    try:
        worker.consume()
    finally:
        if connection.is_open:
            connection.close()


if __name__ == "__main__":
    main()
