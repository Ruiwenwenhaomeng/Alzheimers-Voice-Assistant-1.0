from __future__ import annotations

import importlib
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .quality import AudioQuality


@dataclass(frozen=True)
class EngineResult:
    transcription: str
    report: str
    risk_level: str
    risk_score: float | None
    feature_highlights: list[str]
    model_version: str


class ScreeningEngine(Protocol):
    def analyze(self, audio_path: Path, quality: AudioQuality) -> EngineResult:
        ...


class QualityOnlyEngine:
    """Safe fallback used until a validated ASR and screening model are configured."""

    model_version = "quality-only-v1"

    def analyze(self, audio_path: Path, quality: AudioQuality) -> EngineResult:
        if quality.passed:
            report = (
                "录音质量检查已通过，但当前参考服务未配置经过验证的语音转写和认知风险模型。"
                "本次结果无法评估认知风险，请勿据此判断是否患病。"
            )
        else:
            report = "录音质量不足，无法进行认知风险分析。请根据质量提示重新采集。"
        return EngineResult(
            transcription="[转写模型未配置]",
            report=report,
            risk_level="INCONCLUSIVE",
            risk_score=None,
            feature_highlights=[],
            model_version=self.model_version,
        )


def load_engine(factory_path: str | None = None) -> ScreeningEngine:
    """Load `module:function` from SCREENING_ENGINE_FACTORY, or use the safe fallback."""
    configured = factory_path or os.getenv("SCREENING_ENGINE_FACTORY")
    if not configured:
        return QualityOnlyEngine()
    module_name, separator, factory_name = configured.partition(":")
    if not separator or not module_name or not factory_name:
        raise ValueError("SCREENING_ENGINE_FACTORY 必须使用 module:function 格式")
    module = importlib.import_module(module_name)
    factory = getattr(module, factory_name, None)
    if not callable(factory):
        raise ValueError("配置的筛查引擎工厂不可调用")
    engine = factory()
    if not callable(getattr(engine, "analyze", None)):
        raise ValueError("筛查引擎必须实现 analyze(audio_path, quality)")
    return engine
