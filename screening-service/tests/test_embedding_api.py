from app.embedding_main import create_embedding_app, resolve_embedding_device


class FakeEncoder:
    def encode(self, texts):
        return [[float(index), 1.0] for index, _ in enumerate(texts)]


def test_embedding_api_returns_openai_compatible_shape():
    client = create_embedding_app(FakeEncoder()).test_client()

    response = client.post(
        "/v1/embeddings",
        json={"model": "BAAI/bge-m3", "input": ["问题一", "问题二"]},
    )

    assert response.status_code == 200
    payload = response.get_json()
    assert payload["object"] == "list"
    assert payload["data"][0] == {
        "object": "embedding",
        "index": 0,
        "embedding": [0.0, 1.0],
    }


def test_embedding_api_rejects_empty_input():
    client = create_embedding_app(FakeEncoder()).test_client()

    response = client.post("/v1/embeddings", json={"input": []})

    assert response.status_code == 400


def test_embedding_health_reports_cpu_device(monkeypatch):
    monkeypatch.setenv("RAG_EMBEDDING_DEVICE", "cpu")

    response = create_embedding_app(FakeEncoder()).test_client().get("/health")

    assert response.get_json()["device"] == "cpu"


def test_embedding_cuda_device_uses_configured_index(monkeypatch):
    monkeypatch.setenv("RAG_EMBEDDING_DEVICE", "cuda")
    monkeypatch.setenv("RAG_EMBEDDING_DEVICE_INDEX", "1")

    assert resolve_embedding_device() == "cuda:1"
