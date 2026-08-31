from uuid import uuid4

from app.contracts.screening_events import ScreeningEvent


def event_message():
    return {
        "eventId": str(uuid4()),
        "eventType": "screening.requested.v1",
        "schemaVersion": 1,
        "taskId": str(uuid4()),
        "userId": 7,
        "audioId": 8,
        "traceId": str(uuid4()),
        "attempt": 0,
        "occurredAt": "2026-08-21T12:00:00Z",
        "payload": {"audioName": "sample.wav"},
    }


def test_parses_cross_language_event_and_creates_deterministic_stage_id():
    event = ScreeningEvent.from_message(event_message())

    first = event.next_event("screening.analysis.completed.v1", "analysis-completed")
    second = event.next_event("screening.analysis.completed.v1", "analysis-completed")

    assert first["eventId"] == second["eventId"]
    assert first["taskId"] == event.task_id
    assert first["schemaVersion"] == 1


def test_retry_message_keeps_task_attempt_and_increments_delivery_attempt():
    event = ScreeningEvent.from_message(event_message())

    retry = event.retry_message()

    assert retry["attempt"] == 0
    assert retry["eventType"] == "screening.requested.v1"
    assert retry["payload"]["_deliveryAttempt"] == 1
