package com.alz.screening.messaging;

import com.alz.screening.domain.ScreeningEvent;
import com.alz.screening.persistence.ConsumedEventMapper;
import com.alz.screening.persistence.ScreeningTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreeningStatusListenerTest {

    @Test
    void projectsFeatureStageOnlyAfterClaimingEvent() {
        ScreeningTaskMapper taskMapper = mock(ScreeningTaskMapper.class);
        ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
        ScreeningStatusListener listener = new ScreeningStatusListener(taskMapper, consumed);
        ScreeningEvent event = new ScreeningEvent(
                "0c640965-f423-4196-aae2-6392ad99de55",
                "screening.features.started.v1", 1,
                "62f7d621-295d-4d43-83f1-09ea40196a1a", 7L, 8L,
                "5b351bbc-7894-4f34-b977-9626d1648534", 0, Instant.now(), Map.of());
        when(consumed.claim("java-status-v1", event.eventId())).thenReturn(1);

        listener.project(event);

        verify(taskMapper).advance(event.taskId(), event.attempt(), "FEATURE_EXTRACTING", "FEATURES", 50);
    }

    @Test
    void projectsWorkerCancellationAsTerminalState() {
        ScreeningTaskMapper taskMapper = mock(ScreeningTaskMapper.class);
        ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
        ScreeningStatusListener listener = new ScreeningStatusListener(taskMapper, consumed);
        ScreeningEvent event = new ScreeningEvent(
                "1c640965-f423-4196-aae2-6392ad99de55",
                "screening.cancelled.v1", 1,
                "72f7d621-295d-4d43-83f1-09ea40196a1a", 7L, 8L,
                "6b351bbc-7894-4f34-b977-9626d1648534", 0, Instant.now(), Map.of());
        when(consumed.claim("java-status-v1", event.eventId())).thenReturn(1);

        listener.project(event);

        verify(taskMapper).markCancelled(event.taskId(), event.attempt());
    }
}
