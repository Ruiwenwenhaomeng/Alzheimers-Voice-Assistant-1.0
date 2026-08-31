from __future__ import annotations

import math
import wave
from array import array
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class AudioQuality:
    duration_seconds: float
    sample_rate: int
    channels: int
    rms_dbfs: float
    clipping_ratio: float
    silence_ratio: float
    passed: bool
    issues: list[str]

    def metrics(self) -> dict[str, float | int]:
        values = asdict(self)
        values.pop("passed")
        values.pop("issues")
        return values


class WavQualityAnalyzer:
    """Performs signal-quality checks only; it does not estimate cognitive risk."""

    def __init__(
        self,
        min_duration_seconds: float = 20.0,
        max_duration_seconds: float = 300.0,
        min_sample_rate: int = 8_000,
        max_file_bytes: int = 50 * 1024 * 1024,
    ) -> None:
        self.min_duration_seconds = min_duration_seconds
        self.max_duration_seconds = max_duration_seconds
        self.min_sample_rate = min_sample_rate
        self.max_file_bytes = max_file_bytes

    def analyze(self, path: Path) -> AudioQuality:
        if not path.is_file():
            raise ValueError("音频文件不存在")
        if path.stat().st_size > self.max_file_bytes:
            raise ValueError("音频文件过大")

        sample_count = 0
        square_sum = 0.0
        clipped_count = 0
        silent_count = 0
        try:
            with wave.open(str(path), "rb") as wav_file:
                if wav_file.getcomptype() != "NONE":
                    raise ValueError("仅支持未压缩 PCM WAV")
                channels = wav_file.getnchannels()
                sample_width = wav_file.getsampwidth()
                sample_rate = wav_file.getframerate()
                frame_count = wav_file.getnframes()
                if channels not in (1, 2):
                    raise ValueError("仅支持单声道或双声道 WAV")
                if sample_width not in (1, 2, 3, 4):
                    raise ValueError("不支持该 PCM 位深")
                if sample_rate <= 0 or frame_count <= 0:
                    raise ValueError("WAV 音频没有有效采样")

                while True:
                    raw_frames = wav_file.readframes(8192)
                    if not raw_frames:
                        break
                    samples = self._decode_pcm(raw_frames, sample_width)
                    sample_count += len(samples)
                    square_sum += sum(sample * sample for sample in samples)
                    clipped_count += sum(abs(sample) >= 0.99 for sample in samples)
                    silent_count += sum(abs(sample) <= 0.01 for sample in samples)
        except (wave.Error, EOFError) as exception:
            raise ValueError("无法读取 WAV 音频") from exception

        if sample_count == 0:
            raise ValueError("WAV 音频没有有效采样")

        duration = frame_count / sample_rate
        square_mean = square_sum / sample_count
        rms = math.sqrt(square_mean)
        rms_dbfs = 20 * math.log10(max(rms, 1e-12))
        clipping_ratio = clipped_count / sample_count
        silence_ratio = silent_count / sample_count

        issues: list[str] = []
        if duration < self.min_duration_seconds:
            issues.append(f"录音时长不足 {self.min_duration_seconds:g} 秒")
        if duration > self.max_duration_seconds:
            issues.append(f"录音时长超过 {self.max_duration_seconds:g} 秒")
        if sample_rate < self.min_sample_rate:
            issues.append(f"采样率低于 {self.min_sample_rate} Hz")
        if rms_dbfs < -45:
            issues.append("录音音量过低")
        if clipping_ratio > 0.01:
            issues.append("录音存在明显削波失真")
        if silence_ratio > 0.80:
            issues.append("录音中静音比例过高")

        return AudioQuality(
            duration_seconds=round(duration, 3),
            sample_rate=sample_rate,
            channels=channels,
            rms_dbfs=round(rms_dbfs, 3),
            clipping_ratio=round(clipping_ratio, 6),
            silence_ratio=round(silence_ratio, 6),
            passed=not issues,
            issues=issues,
        )

    def _decode_pcm(self, raw: bytes, width: int) -> list[float]:
        if width == 1:
            return [(value - 128) / 128.0 for value in raw]
        if width == 2:
            values = array("h")
            values.frombytes(raw)
            return [value / 32768.0 for value in values]
        if width == 4:
            values = array("i")
            values.frombytes(raw)
            return [value / 2147483648.0 for value in values]

        decoded: list[float] = []
        for offset in range(0, len(raw) - 2, 3):
            value = int.from_bytes(raw[offset : offset + 3], "little", signed=False)
            if value & 0x800000:
                value -= 0x1000000
            decoded.append(value / 8388608.0)
        return decoded
