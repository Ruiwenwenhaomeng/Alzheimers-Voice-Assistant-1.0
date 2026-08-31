from __future__ import annotations

import math
import struct
import wave
from pathlib import Path

import pytest


@pytest.fixture
def wav_factory():
    def create(
        path: Path,
        duration_seconds: float = 1.0,
        sample_rate: int = 16_000,
        amplitude: float = 0.25,
        frequency: float = 220.0,
    ) -> Path:
        frame_count = int(duration_seconds * sample_rate)
        frames = bytearray()
        for index in range(frame_count):
            sample = amplitude * math.sin(2 * math.pi * frequency * index / sample_rate)
            frames.extend(struct.pack("<h", int(sample * 32767)))
        with wave.open(str(path), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(sample_rate)
            output.writeframes(bytes(frames))
        return path

    return create
