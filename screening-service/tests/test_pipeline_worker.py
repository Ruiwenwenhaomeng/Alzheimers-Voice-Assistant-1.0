from __future__ import annotations

import json
from types import SimpleNamespace
from uuid import uuid4

from app.artifacts.store import ArtifactStore
from app.contracts.screening_events import ScreeningEvent
from app.workers.pipeline import PipelineWorker, STAGES


class FakeChannel:
    def __init__(self):
        self.published = []
        self.acked = []
        self.rejected = []

    def basic_publish(self, **kwargs):
        self.published.append(kwargs)
        return True

    def basic_ack(self, delivery_tag):
        self.acked.append(delivery_tag)

    def basic_reject(self, delivery_tag, requeue=False):
        self.rejected.append((delivery_tag, requeue))


def requested_event(task_id: str) -> ScreeningEvent:
    return ScreeningEvent.from_message(
        {
            "eventId": str(uuid4()),
            "eventType": "screening.requested.v1",
            "schemaVersion": 1,
            "taskId": task_id,
            "userId": 7,
            "audioId": 8,
            "traceId": str(uuid4()),
            "attempt": 0,
            "occurredAt": "2026-08-31T12:00:00Z",
            "payload": {"audioName": "sample.wav"},
        }
    )


def published_event(channel: FakeChannel, routing_key: str) -> ScreeningEvent:
    message = next(item for item in channel.published if item["routing_key"] == routing_key)
    return ScreeningEvent.from_message(json.loads(message["body"].decode("utf-8")))


def test_three_stage_pipeline_releases_transcriber_before_features_and_llm(tmp_path):
    audio_root = tmp_path / "audio"
    audio_root.mkdir()
    (audio_root / "sample.wav").write_bytes(b"test-audio")
    store = ArtifactStore(tmp_path / "artifacts")
    calls = []

    transcription_channel = FakeChannel()
    quality = SimpleNamespace(
        passed=True,
        issues=[],
        metrics=lambda: {"duration_seconds": 30.0, "sample_rate": 16000},
    )
    transcription_worker = PipelineWorker(
        STAGES["transcription"],
        transcription_channel,
        audio_root,
        store,
        3,
        quality_analyzer=SimpleNamespace(analyze=lambda _path: quality),
        transcribe=lambda _path, check: (check(), calls.append("transcription"), "测试文本")[2],
    )
    transcription_worker._process(requested_event(str(uuid4())))
    assert calls == ["transcription"]

    transcription_completed = published_event(
        transcription_channel, "screening.transcription.completed.v1"
    )
    features_channel = FakeChannel()
    feature_worker = PipelineWorker(
        STAGES["features"],
        features_channel,
        audio_root,
        store,
        3,
        extract_mfcc=lambda _path: (calls.append("features"), [0.1, 0.2])[1],
        extract_semantic=lambda _text: {"ttr": 0.5, "avg_sentence_length": 4.0},
    )
    feature_worker._process(transcription_completed)
    assert calls == ["transcription", "features"]

    features_completed = published_event(features_channel, "screening.features.completed.v1")
    llm_channel = FakeChannel()
    llm_worker = PipelineWorker(
        STAGES["llm"],
        llm_channel,
        audio_root,
        store,
        3,
        call_llm=lambda _text, _mfcc, _semantic: (calls.append("llm"), "筛查报告")[1],
    )
    llm_worker._process(features_completed)

    assert calls == ["transcription", "features", "llm"]
    completed = published_event(llm_channel, "screening.analysis.completed.v1")
    assert completed.attempt == 0
    analysis = store.read_document(
        completed.task_id,
        "analysis.json",
        completed.payload["artifactUri"],
        completed.payload["sha256"],
    )
    assert analysis["transcription"] == "测试文本"
    assert analysis["report"] == "筛查报告"


def test_stage_retry_keeps_user_attempt_and_increments_delivery_attempt(tmp_path):
    event = requested_event(str(uuid4()))
    retry = ScreeningEvent.from_message(event.retry_message())

    assert retry.attempt == event.attempt
    assert retry.delivery_attempt == 1
    assert retry.event_type == event.event_type


def test_cancelled_attempt_does_not_enter_transcription_stage(tmp_path):
    task_id = str(uuid4())
    event = requested_event(task_id)
    store = ArtifactStore(tmp_path / "artifacts")
    marker = store.cancellation_path(task_id)
    marker.parent.mkdir(parents=True)
    marker.write_text("0", encoding="utf-8")
    channel = FakeChannel()
    worker = PipelineWorker(
        STAGES["transcription"],
        channel,
        tmp_path / "audio",
        store,
        3,
        quality_analyzer=SimpleNamespace(analyze=lambda _path: (_ for _ in ()).throw(AssertionError())),
        transcribe=lambda *_args: (_ for _ in ()).throw(AssertionError()),
    )
    body = {
        "eventId": event.event_id,
        "eventType": event.event_type,
        "schemaVersion": event.schema_version,
        "taskId": event.task_id,
        "userId": event.user_id,
        "audioId": event.audio_id,
        "traceId": event.trace_id,
        "attempt": event.attempt,
        "occurredAt": event.occurred_at,
        "payload": event.payload,
    }

    worker._on_message(
        channel,
        SimpleNamespace(delivery_tag=17),
        None,
        json.dumps(body).encode("utf-8"),
    )

    assert channel.acked == [17]
    assert not channel.rejected
    assert [item["routing_key"] for item in channel.published] == ["screening.cancelled.v1"]
