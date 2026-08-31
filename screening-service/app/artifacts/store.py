from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any, Mapping
from uuid import UUID


class ArtifactStore:
    def __init__(self, root: Path) -> None:
        self._root = root.resolve()
        self._root.mkdir(parents=True, exist_ok=True)

    def analysis_path(self, task_id: str) -> Path:
        return self.task_path(task_id, "analysis.json")

    def task_path(self, task_id: str, filename: str) -> Path:
        UUID(task_id)
        if not filename or Path(filename).name != filename:
            raise ValueError("invalid artifact filename")
        task_directory = (self._root / task_id).resolve()
        task_directory.relative_to(self._root)
        return task_directory / filename

    def cancellation_path(self, task_id: str) -> Path:
        return self.task_path(task_id, "cancel.requested")

    def is_cancellation_requested(self, task_id: str, attempt: int) -> bool:
        marker = self.cancellation_path(task_id)
        if not marker.is_file():
            return False
        try:
            cancelled_attempt = int(marker.read_text("utf-8").strip())
        except (OSError, ValueError):
            return True
        return attempt <= cancelled_attempt

    def write_analysis(self, task_id: str, result: Mapping[str, Any]) -> tuple[str, str]:
        return self.write_document(task_id, "analysis.json", result)

    def write_document(
        self, task_id: str, filename: str, result: Mapping[str, Any]
    ) -> tuple[str, str]:
        destination = self.task_path(task_id, filename)
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".json.tmp")
        encoded = json.dumps(
            dict(result), ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        with temporary.open("wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)
        return f"{task_id}/{filename}", hashlib.sha256(encoded).hexdigest()

    def existing_analysis(self, task_id: str) -> tuple[str, str] | None:
        return self.existing_document(task_id, "analysis.json")

    def existing_document(self, task_id: str, filename: str) -> tuple[str, str] | None:
        path = self.task_path(task_id, filename)
        if not path.is_file():
            return None
        data = path.read_bytes()
        json.loads(data.decode("utf-8"))
        return f"{task_id}/{filename}", hashlib.sha256(data).hexdigest()

    def read_document(
        self, task_id: str, filename: str, artifact_uri: str, expected_sha256: str
    ) -> dict[str, Any]:
        if artifact_uri != f"{task_id}/{filename}":
            raise ValueError("invalid artifact URI")
        if not expected_sha256 or len(expected_sha256) != 64:
            raise ValueError("invalid artifact checksum")
        path = self.task_path(task_id, filename)
        data = path.read_bytes()
        actual = hashlib.sha256(data).hexdigest()
        if actual.lower() != expected_sha256.lower():
            raise ValueError("artifact checksum mismatch")
        payload = json.loads(data.decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("artifact payload must be an object")
        return payload

    def existing_analysis_model(self, task_id: str) -> str | None:
        path = self.analysis_path(task_id)
        if not path.is_file():
            return None
        payload = json.loads(path.read_text("utf-8"))
        if not isinstance(payload, dict):
            return None
        model_version = payload.get("model_version")
        return str(model_version) if model_version else None

    def discard_analysis(self, task_id: str) -> None:
        self.analysis_path(task_id).unlink(missing_ok=True)
