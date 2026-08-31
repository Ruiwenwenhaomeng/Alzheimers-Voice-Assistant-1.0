package com.alz.screening.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScreeningMessagingConfigTest {

    @Test
    void createsIsolatedBoundedListenerFactoriesWhenAsyncScreeningIsEnabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "app.screening.async.enabled=true",
                    "app.screening.async.result-concurrency=2",
                    "app.screening.async.status-concurrency=2",
                    "app.screening.async.pdf-concurrency=1",
                    "app.screening.async.listener-queue-capacity=4",
                    "spring.rabbitmq.listener.simple.prefetch=1"
            ).applyTo(context);
            context.registerBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ScreeningMessagingConfig.class);
            context.refresh();

            assertThat(context.getBeansOfType(SimpleRabbitListenerContainerFactory.class))
                    .containsKeys("screeningResultContainerFactory",
                            "screeningStatusContainerFactory", "screeningPdfContainerFactory");
            assertThat(context.getBeansOfType(TaskExecutor.class))
                    .containsKeys("screeningResultExecutor", "screeningStatusExecutor", "screeningPdfExecutor");
            assertThat(context.getBean("transcriptionBinding")).isNotNull();
            assertThat(context.getBean("featuresBinding")).isNotNull();
            assertThat(context.getBean("llmBinding")).isNotNull();
            assertThat(context.getBean("deadLetterBinding")).isNotNull();
        }
    }
}
