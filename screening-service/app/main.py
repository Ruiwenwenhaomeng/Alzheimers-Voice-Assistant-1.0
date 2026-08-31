from __future__ import annotations

import os
from pathlib import Path

from flask import Flask, jsonify, request

from .engine import ScreeningEngine, load_engine
from .quality import WavQualityAnalyzer


def create_app(
    audio_root: Path | None = None,
    analyzer: WavQualityAnalyzer | None = None,
    engine: ScreeningEngine | None = None,
) -> Flask:
    app = Flask(__name__)
    default_root = Path(__file__).resolve().parents[2] / "data" / "audio"
    allowed_root = (audio_root or Path(os.getenv("AUDIO_ROOT", default_root))).resolve()
    quality_analyzer = analyzer or WavQualityAnalyzer()
    screening_engine = engine or load_engine()

    @app.get("/health")
    def health():
        model_version = getattr(screening_engine, "model_version", "unknown")
        return jsonify(
            status="UP",
            engine=screening_engine.__class__.__name__,
            model_version=model_version,
            ai_ready=screening_engine.__class__.__name__ != "QualityOnlyEngine",
            deepseek_configured=bool(os.getenv("DEEPSEEK_API_KEY", "").strip()),
            whisper_model=os.getenv("WHISPER_MODEL_SIZE", "large-v2"),
            whisper_device=os.getenv("WHISPER_DEVICE", "cpu"),
            whisper_device_index=os.getenv("WHISPER_DEVICE_INDEX", "0"),
            whisper_compute_type=os.getenv("WHISPER_COMPUTE_TYPE", "int8"),
            audio_root=str(allowed_root),
        )

    @app.post("/api/diagnosis")
    def diagnose():
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict) or not payload.get("audio_path"):
            return jsonify(message="audio_path 不能为空"), 400

        try:
            audio_path = _resolve_allowed_path(Path(str(payload["audio_path"])), allowed_root)
            quality = quality_analyzer.analyze(audio_path)
            result = screening_engine.analyze(audio_path, quality)
        except ValueError as exception:
            return jsonify(message=str(exception)), 400
        except Exception:
            app.logger.exception("screening service failed")
            return jsonify(message="语音筛查服务内部错误"), 500

        return jsonify(
            transcription=result.transcription,
            report=result.report,
            risk_level=result.risk_level,
            risk_score=result.risk_score,
            quality_passed=quality.passed,
            quality_issues=quality.issues,
            quality_metrics=quality.metrics(),
            feature_highlights=result.feature_highlights,
            model_version=result.model_version,
        )

    return app


def _resolve_allowed_path(requested_path: Path, allowed_root: Path) -> Path:
    candidate = requested_path.resolve()
    try:
        candidate.relative_to(allowed_root)
    except ValueError as exception:
        raise ValueError("音频路径不在允许的数据目录内") from exception
    return candidate


if __name__ == "__main__":
    create_app().run(host="127.0.0.1", port=5000)
