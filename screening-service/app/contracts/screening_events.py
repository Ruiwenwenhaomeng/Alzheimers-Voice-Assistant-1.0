from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping
from uuid import NAMESPACE_URL, UUID, uuid5


@dataclass(frozen=True)
class ScreeningEvent:
    event_id: str
    event_type: str
    schema_version: int
    task_id: str
    user_id: int
    audio_id: int
    trace_id: str
    attempt: int
    occurred_at: str
    payload: dict[str, Any]

    @classmethod
    def from_message(cls, value: Mapping[str, Any]) -> "ScreeningEvent":
        required = (
            "eventId",
            "eventType",
            "schemaVersion",
            "taskId",
            "userId",
            "audioId",
            "traceId",
            "attempt",
            "occurredAt",
        )
        missing = [key for key in required if key not in value]
        if missing:
            raise ValueError(f"event envelope missing fields: {','.join(missing)}")
        event = cls(
            event_id=str(value["eventId"]),
            event_type=str(value["eventType"]),
            schema_version=int(value["schemaVersion"]),
            task_id=str(value["taskId"]),
            user_id=int(value["userId"]),
            audio_id=int(value["audioId"]),
            trace_id=str(value["traceId"]),
            attempt=max(0, int(value["attempt"])),
            occurred_at=str(value["occurredAt"]),
            payload=dict(value.get("payload") or {}),
        )
        event.validate()
        return event

    def validate(self) -> None:
        UUID(self.event_id)
        UUID(self.task_id)
        UUID(self.trace_id)
        if self.schema_version != 1:
            raise ValueError("unsupported screening event schema")
        if self.event_type != "screening.requested.v1":
            raise ValueError("unsupported screening event type")
        if self.user_id <= 0 or self.audio_id <= 0:
            raise ValueError("event userId/audioId must be positive")

    def next_event(
        self,
        event_type: str,
        stage: str,
        payload: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        return {
            "eventId": str(uuid5(NAMESPACE_URL, f"{self.task_id}:{stage}:v1:{self.attempt}")),
            "eventType": event_type,
            "schemaVersion": 1,
            "taskId": self.task_id,
            "userId": self.user_id,
            "audioId": self.audio_id,
            "traceId": self.trace_id,
            "attempt": self.attempt,
            "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "payload": dict(payload or {}),
        }

    def retry_message(self) -> dict[str, Any]:
        value = self.next_event(
            "screening.requested.v1", f"requested-retry-{self.attempt + 1}", self.payload
        )
        value["attempt"] = self.attempt + 1
        return value
