from __future__ import annotations

import os
from functools import lru_cache
from typing import Protocol, Sequence

from flask import Flask, jsonify, request

from .gpu_runtime import configure_cuda_dll_search_paths, parse_device_index


class TextEncoder(Protocol):
    def encode(self, texts: Sequence[str]) -> list[list[float]]:
        ...


class SentenceTransformerEncoder:
    def __init__(self) -> None:
        configure_cuda_dll_search_paths()
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as exception:
            raise RuntimeError(
                "Embedding service requires screening-service/embedding-requirements.txt"
            ) from exception

        model_name = os.getenv("RAG_EMBEDDING_MODEL", "BAAI/bge-m3")
        device = resolve_embedding_device()
        self._model = SentenceTransformer(model_name, device=device)
        self._batch_size = max(1, int(os.getenv("RAG_EMBEDDING_BATCH_SIZE", "16")))

    def encode(self, texts: Sequence[str]) -> list[list[float]]:
        vectors = self._model.encode(
            list(texts),
            batch_size=self._batch_size,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        return vectors.astype(float).tolist()


@lru_cache(maxsize=1)
def load_encoder() -> TextEncoder:
    return SentenceTransformerEncoder()


def resolve_embedding_device() -> str:
    device = os.getenv("RAG_EMBEDDING_DEVICE", "cpu").strip().lower()
    if device == "cuda":
        index = parse_device_index(os.getenv("RAG_EMBEDDING_DEVICE_INDEX", "0"))
        return f"cuda:{index}"
    return device


def create_embedding_app(encoder: TextEncoder | None = None) -> Flask:
    app = Flask(__name__)
    configured_model = os.getenv("RAG_EMBEDDING_MODEL", "BAAI/bge-m3")
    configured_api_key = os.getenv("RAG_EMBEDDING_API_KEY", "").strip()
    configured_device = resolve_embedding_device()

    @app.get("/health")
    def health():
        return jsonify(status="UP", model=configured_model, device=configured_device)

    @app.post("/v1/embeddings")
    def embeddings():
        if configured_api_key:
            authorization = request.headers.get("Authorization", "")
            if authorization != f"Bearer {configured_api_key}":
                return jsonify(error={"message": "unauthorized"}), 401

        payload = request.get_json(silent=True)
        if not isinstance(payload, dict):
            return jsonify(error={"message": "JSON body is required"}), 400
        requested_model = str(payload.get("model") or "")
        if requested_model and requested_model != configured_model:
            return jsonify(error={"message": "unsupported embedding model"}), 400

        value = payload.get("input")
        texts = [value] if isinstance(value, str) else value
        if not isinstance(texts, list) or not texts or len(texts) > 64:
            return jsonify(error={"message": "input must contain 1 to 64 texts"}), 400
        if any(not isinstance(text, str) or not text.strip() or len(text) > 4000 for text in texts):
            return jsonify(error={"message": "each input must be non-empty and at most 4000 characters"}), 400

        try:
            active_encoder = encoder or load_encoder()
            vectors = active_encoder.encode(texts)
        except Exception:
            app.logger.exception("embedding generation failed")
            return jsonify(error={"message": "embedding service unavailable"}), 503
        if len(vectors) != len(texts):
            app.logger.error("embedding count mismatch")
            return jsonify(error={"message": "embedding service returned invalid data"}), 503

        return jsonify(
            object="list",
            model=configured_model,
            data=[
                {"object": "embedding", "index": index, "embedding": vector}
                for index, vector in enumerate(vectors)
            ],
        )

    return app


if __name__ == "__main__":
    port = int(os.getenv("RAG_EMBEDDING_PORT", "7997"))
    # Load/download the model before opening the port so a listening socket means ready.
    load_encoder()
    create_embedding_app().run(host="127.0.0.1", port=port)
