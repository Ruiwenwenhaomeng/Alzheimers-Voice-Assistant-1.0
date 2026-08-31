from __future__ import annotations

import logging
import os
import re
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable

import jieba
import librosa
import numpy as np
from openai import OpenAI

from .gpu_runtime import configure_cuda_dll_search_paths, parse_device_index

configure_cuda_dll_search_paths()

try:
    import zhconv
except ImportError:  # pragma: no cover - optional dependency
    zhconv = None

try:
    from faster_whisper import WhisperModel
except ImportError as exception:  # pragma: no cover - startup config error
    raise RuntimeError(
        "faster-whisper is required for the AD diagnosis engine. "
        "Run `python -m pip install -r screening-service/requirements.txt`."
    ) from exception


logger = logging.getLogger(__name__)
progress_status: dict[str, int] = {}

os.environ.setdefault("HF_ENDPOINT", "https://huggingface.co")


@lru_cache(maxsize=1)
def get_whisper_model() -> WhisperModel:
    model_size = os.getenv("WHISPER_MODEL_SIZE", "large-v2")
    device = os.getenv("WHISPER_DEVICE", "cpu")
    compute_type = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
    device_index = parse_device_index(os.getenv("WHISPER_DEVICE_INDEX", "0"))
    logger.info(
        "Loading faster-whisper model %s on %s:%s computeType=%s",
        model_size,
        device,
        device_index,
        compute_type,
    )
    return WhisperModel(
        model_size,
        device=device,
        device_index=device_index,
        compute_type=compute_type,
    )


def transcribe_audio(
    audio_path: str | Path, check_cancelled: Callable[[], None] = lambda: None
) -> str:
    model = get_whisper_model()
    check_cancelled()
    segments, _ = model.transcribe(
        str(audio_path),
        language="zh",
        beam_size=int(os.getenv("WHISPER_BEAM_SIZE", "10")),
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
    )
    text_parts: list[str] = []
    for segment in segments:
        check_cancelled()
        text_parts.append(segment.text)
    check_cancelled()
    return clean_text(ensure_simplified("".join(text_parts)))


def extract_mfcc(audio_path: str | Path) -> list[float]:
    y, sr = librosa.load(str(audio_path), sr=None)
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=13)
    return mfcc.mean(axis=1).astype(float).tolist()


def extract_semantic_features(text: str) -> dict[str, float]:
    if not text:
        return {
            "avg_word_length": 0.0,
            "ttr": 0.0,
            "avg_sentence_length": 0.0,
            "modal_particle_freq": 0.0,
        }

    words = [word for word in jieba.cut(text) if word.strip()]
    word_count = len(words)
    char_count = len(text)
    unique_words = set(words)
    sentences = [
        sentence
        for sentence in re.split(r"[。！？；…\n]+", text)
        if sentence.strip()
    ]
    modal_particles = {
        "啊",
        "呀",
        "吧",
        "呢",
        "嘛",
        "呃",
        "嗯",
        "这个",
        "那个",
    }
    modal_count = sum(1 for word in words if word in modal_particles)

    return {
        "avg_word_length": char_count / word_count if word_count else 0.0,
        "ttr": len(unique_words) / word_count if word_count else 0.0,
        "avg_sentence_length": word_count / max(1, len(sentences)),
        "modal_particle_freq": modal_count / word_count if word_count else 0.0,
    }


def ensure_simplified(text: str) -> str:
    if zhconv is not None and text:
        return zhconv.convert(text, "zh-cn")
    return text


def clean_text(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()


def call_deepseek_api(
    transcription: str,
    mfcc_features: list[float],
    semantic_features: dict[str, float],
) -> str:
    api_key = os.getenv("DEEPSEEK_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY is required for report generation")

    client = OpenAI(
        api_key=api_key,
        base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
    )
    model = os.getenv("DEEPSEEK_SCREENING_MODEL", "deepseek-v4-flash")
    max_tokens = int(os.getenv("DEEPSEEK_SCREENING_MAX_TOKENS", "1500"))

    prompt = f"""
请作为阿尔茨海默病语音健康顾问，根据以下语音转写文本和特征生成一份谨慎的筛查报告。
报告只能作为健康筛查参考，不能给出确诊结论，也不能替代医生面诊。

【音频转写文本】
{transcription}

【声学特征】MFCC 均值，13 维
{np.round(np.array(mfcc_features), 4).tolist()}

【语义特征】
- 平均词长：{semantic_features["avg_word_length"]:.2f}
- 词汇多样性 TTR：{semantic_features["ttr"]:.2f}
- 平均句长：{semantic_features["avg_sentence_length"]:.2f}
- 语气/填充词频率：{semantic_features["modal_particle_freq"]:.2f}

请用中文输出，包含：1. 概述 2. 特征分析 3. 筛查提示 4. 建议。
"""
    response = client.chat.completions.create(
        model=model,
        messages=[
            {
                "role": "system",
                "content": (
                    "你是一名严谨的认知健康筛查助手。你会基于语音和语言特征"
                    "给出风险提示，但明确说明结果不是医学诊断。"
                ),
            },
            {"role": "user", "content": prompt},
        ],
        temperature=float(os.getenv("DEEPSEEK_SCREENING_TEMPERATURE", "0.7")),
        max_tokens=max_tokens,
    )
    return ensure_simplified(response.choices[0].message.content or "").strip()


def process_audio(audio_path: str | Path, task_id: str | None = None) -> dict[str, Any]:
    audio_path = Path(audio_path)
    if not audio_path.is_file():
        raise FileNotFoundError(f"Audio file does not exist: {audio_path}")

    logger.info("Starting AD voice diagnosis for %s", audio_path)
    if task_id:
        progress_status[task_id] = 5

    transcription = transcribe_audio(audio_path)
    if task_id:
        progress_status[task_id] = 40

    mfcc_features = extract_mfcc(audio_path)
    if task_id:
        progress_status[task_id] = 60

    semantic_features = extract_semantic_features(transcription)
    if task_id:
        progress_status[task_id] = 75

    report = call_deepseek_api(transcription, mfcc_features, semantic_features)
    if task_id:
        progress_status[task_id] = 100

    return {
        "transcription": transcription,
        "mfcc_features": mfcc_features,
        "semantic_features": semantic_features,
        "report": report,
    }
