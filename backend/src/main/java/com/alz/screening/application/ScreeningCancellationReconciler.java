package com.alz.screening.application;

import com.alz.screening.persistence.ScreeningTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "app.screening.async", name = "enabled", havingValue = "true")
public class ScreeningCancellationReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreeningCancellationReconciler.class);

    private final ScreeningTaskMapper taskMapper;
    private final long timeoutMs;

    public ScreeningCancellationReconciler(
            ScreeningTaskMapper taskMapper,
            @Value("${app.screening.async.cancel-timeout-ms:120000}") long timeoutMs) {
        this.taskMapper = taskMapper;
        this.timeoutMs = Math.max(5000, timeoutMs);
    }

    @Scheduled(fixedDelayString = "${app.screening.async.cancel-reconcile-delay-ms:5000}")
    @Transactional
    public void reconcile() {
        LocalDateTime cutoff = LocalDateTime.now().minusNanos(timeoutMs * 1_000_000L);
        int updated = taskMapper.markTimedOutCancellations(cutoff);
        if (updated > 0) {
            LOGGER.warn("已将 {} 个超时取消请求收敛为 CANCELLED", updated);
        }
    }
}
