import json
from uuid import uuid4

from app.artifacts.store import ArtifactStore


def test_analysis_artifact_is_atomic_and_idempotently_readable(tmp_path):
    task_id = "62f7d621-295d-4d43-83f1-09ea40196a1a"
    store = ArtifactStore(tmp_path)

    uri, sha256 = store.write_analysis(task_id, {"transcription": "测试", "report": "报告"})
    existing = store.existing_analysis(task_id)

    assert uri == f"{task_id}/analysis.json"
    assert existing == (uri, sha256)
    assert not (tmp_path / task_id / "analysis.json.tmp").exists()
    assert json.loads((tmp_path / task_id / "analysis.json").read_text("utf-8"))["report"] == "报告"


def test_quality_only_artifact_can_be_discarded_before_ai_retry(tmp_path):
    task_id = "62f7d621-295d-4d43-83f1-09ea40196a1a"
    store = ArtifactStore(tmp_path)
    store.write_analysis(task_id, {"model_version": "quality-only-v1"})

    assert store.existing_analysis_model(task_id) == "quality-only-v1"

    store.discard_analysis(task_id)

    assert store.existing_analysis(task_id) is None


def test_cancellation_marker_only_applies_through_recorded_attempt(tmp_path):
    store = ArtifactStore(tmp_path)
    task_id = str(uuid4())
    marker = store.cancellation_path(task_id)
    marker.parent.mkdir(parents=True)
    marker.write_text("1", encoding="utf-8")

    assert store.is_cancellation_requested(task_id, 0)
    assert store.is_cancellation_requested(task_id, 1)
    assert not store.is_cancellation_requested(task_id, 2)
