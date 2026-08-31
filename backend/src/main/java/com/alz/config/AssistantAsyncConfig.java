package com.alz.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AssistantAsyncConfig {

    @Bean(name = "assistantMemoryExecutor")
    public ThreadPoolTaskExecutor assistantMemoryExecutor(
            @Value("${app.rag.memory.summary-concurrency:2}") int concurrency,
            @Value("${app.rag.memory.summary-queue-capacity:100}") int queueCapacity) {
        int size = Math.max(1, Math.min(concurrency, 4));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(Math.max(10, queueCapacity));
        executor.setThreadNamePrefix("assistant-memory-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
