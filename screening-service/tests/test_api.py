from __future__ import annotations

from app.main import create_app
from app.engine import QualityOnlyEngine
from app.quality import WavQualityAnalyzer


def test_quality_only_api_returns_inconclusive(tmp_path, wav_factory):
    audio = wav_factory(tmp_path / "sample.wav", duration_seconds=1.0)
    app = create_app(
        audio_root=tmp_path,
        analyzer=WavQualityAnalyzer(min_duration_seconds=0.5),
    )

    response = app.test_client().post(
        "/api/diagnosis", json={"audio_path": str(audio)}
    )

    assert response.status_code == 200
    payload = response.get_json()
    assert payload["quality_passed"] is True
    assert payload["risk_level"] == "INCONCLUSIVE"
    assert payload["risk_score"] is None
    assert payload["model_version"] == "quality-only-v1"


def test_api_rejects_path_outside_allowed_root(tmp_path, wav_factory):
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    outside = wav_factory(tmp_path / "outside.wav", duration_seconds=1.0)
    app = create_app(audio_root=allowed)

    response = app.test_client().post(
        "/api/diagnosis", json={"audio_path": str(outside)}
    )

    assert response.status_code == 400
    assert "允许的数据目录" in response.get_json()["message"]


def test_health_exposes_quality_only_readiness_without_secrets(tmp_path):
    app = create_app(audio_root=tmp_path, engine=QualityOnlyEngine())

    response = app.test_client().get("/health")

    assert response.status_code == 200
    payload = response.get_json()
    assert payload["engine"] == "QualityOnlyEngine"
    assert payload["model_version"] == "quality-only-v1"
    assert payload["ai_ready"] is False
    assert "api_key" not in payload
