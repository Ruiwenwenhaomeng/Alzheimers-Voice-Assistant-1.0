package com.alz.screening.application;

import com.alz.screening.persistence.ScreeningTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ScreeningCancellationReconcilerTest {

    @Test
    void convergesCancellationRequestsOlderThanConfiguredTimeout() {
        ScreeningTaskMapper mapper = mock(ScreeningTaskMapper.class);
        ScreeningCancellationReconciler reconciler =
                new ScreeningCancellationReconciler(mapper, 30_000);
        LocalDateTime before = LocalDateTime.now().minusSeconds(31);

        reconciler.reconcile();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).markTimedOutCancellations(cutoff.capture());
        assertTrue(cutoff.getValue().isAfter(before));
    }
}
