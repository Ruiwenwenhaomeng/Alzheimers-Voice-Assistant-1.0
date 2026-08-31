from __future__ import annotations

import json
from types import SimpleNamespace
from uuid import uuid4

from app.artifacts.store import ArtifactStore
from app.workers.combined import CombinedScreeningWorker


class FakeChannel:
    def __init__(self):
        self.acked = []
        self.rejected = []
        self.published = []

    def basic_ack(self, delivery_tag):
        self.acked.append(delivery_tag)

    def basic_reject(self, delivery_tag, requeue=False):
        self.rejected.append((delivery_tag, requeue))

    def basic_publish(self, **kwargs):
        self.published.append(kwargs)
        return True


def test_cancelled_attempt_is_acked_without_running_audio_pipeline(tmp_path):
    task_id = str(uuid4())
    store = ArtifactStore(tmp_path / "artifacts")
    marker = store.cancellation_path(task_id)
    marker.parent.mkdir(parents=True)
    marker.write_text("0", encoding="utf-8")
    channel = FakeChannel()
    analyzer = SimpleNamespace(analyze=lambda _path: (_ for _ in ()).throw(AssertionError()))
    worker = CombinedScreeningWorker(
        channel=channel,
        audio_root=tmp_path / "audio",
        artifact_store=store,
        quality_analyzer=analyzer,
        engine=SimpleNamespace(analyze=lambda *_args: (_ for _ in ()).throw(AssertionError())),
        max_retries=3,
    )
    message = {
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

    worker._on_message(
        channel,
        SimpleNamespace(delivery_tag=11),
        None,
        json.dumps(message).encode("utf-8"),
    )

    assert channel.acked == [11]
    assert not channel.rejected
    assert len(channel.published) == 1
    assert channel.published[0]["routing_key"] == "screening.cancelled.v1"
