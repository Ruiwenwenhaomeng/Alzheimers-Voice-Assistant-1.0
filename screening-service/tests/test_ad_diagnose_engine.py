from __future__ import annotations

import sys
from types import SimpleNamespace

import pytest

from app.ad_diagnose_engine import DeepSeekVoiceDiagnosisEngine, create_engine
from app.engine import QualityOnlyEngine


def test_factory_uses_safe_fallback_when_ai_is_optional(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    monkeypatch.setenv("SCREENING_REQUIRE_AI", "false")

    assert isinstance(create_engine(), QualityOnlyEngine)


def test_factory_rejects_silent_fallback_when_ai_is_required(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    monkeypatch.setenv("SCREENING_REQUIRE_AI", "true")

    with pytest.raises(RuntimeError, match="DEEPSEEK_API_KEY"):
        create_engine()


def test_staged_analysis_stops_after_transcription_when_cancelled(monkeypatch):
    cancelled = False
    feature_extraction_started = False

    def transcribe(_audio_path, _check_cancelled):
        nonlocal cancelled
        cancelled = True
        return "测试转写"

    def extract_mfcc(_audio_path):
        nonlocal feature_extraction_started
        feature_extraction_started = True
        return []

    def check_cancelled():
        if cancelled:
            raise RuntimeError("cancelled")

    diagnosis = SimpleNamespace(
        transcribe_audio=transcribe,
        extract_mfcc=extract_mfcc,
        extract_semantic_features=lambda _text: {},
        call_deepseek_api=lambda _text, _mfcc, _semantic: "report",
    )
    monkeypatch.setitem(sys.modules, "app.ad_diagnose", diagnosis)

    quality = type("Quality", (), {"passed": True, "issues": []})()
    with pytest.raises(RuntimeError, match="cancelled"):
        DeepSeekVoiceDiagnosisEngine().analyze_staged(
            None, quality, lambda _stage: None, check_cancelled
        )

    assert feature_extraction_started is False
