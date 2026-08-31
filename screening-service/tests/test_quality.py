from __future__ import annotations

import wave

from app.quality import WavQualityAnalyzer


def test_accepts_usable_pcm_wav(tmp_path, wav_factory):
    audio = wav_factory(tmp_path / "usable.wav", duration_seconds=1.0)
    analyzer = WavQualityAnalyzer(min_duration_seconds=0.5)

    quality = analyzer.analyze(audio)

    assert quality.passed is True
    assert quality.sample_rate == 16_000
    assert quality.channels == 1
    assert quality.issues == []


def test_rejects_excessive_silence(tmp_path):
    audio = tmp_path / "silent.wav"
    with wave.open(str(audio), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16_000)
        output.writeframes(b"\x00\x00" * 16_000)

    quality = WavQualityAnalyzer(min_duration_seconds=0.5).analyze(audio)

    assert quality.passed is False
    assert any("静音比例" in issue for issue in quality.issues)


def test_rejects_too_short_recording(tmp_path, wav_factory):
    audio = wav_factory(tmp_path / "short.wav", duration_seconds=0.1)

    quality = WavQualityAnalyzer(min_duration_seconds=1.0).analyze(audio)

    assert quality.passed is False
    assert any("时长不足" in issue for issue in quality.issues)
