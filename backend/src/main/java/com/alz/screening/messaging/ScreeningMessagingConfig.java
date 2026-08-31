package com.alz.screening.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.screening.async", name = "enabled", havingValue = "true")
public class ScreeningMessagingConfig {

    public static final String EXCHANGE = "alz.screening.events.x";
    public static final String DEAD_LETTER_EXCHANGE = "alz.screening.dlx";
    public static final String TRANSCRIPTION_QUEUE = "alz.screening.transcription.q";
    public static final String FEATURES_QUEUE = "alz.screening.features.q";
    public static final String LLM_QUEUE = "alz.screening.llm.q";
    public static final String RESULT_QUEUE = "alz.screening.result.java.q";
    public static final String STATUS_QUEUE = "alz.screening.status.java.q";
    public static final String PDF_QUEUE = "alz.pdf.generate.java.q";

    @Bean
    TopicExchange screeningExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    TopicExchange screeningDeadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue screeningTranscriptionQueue() {
        return durableQueue(TRANSCRIPTION_QUEUE);
    }

    @Bean
    Queue screeningFeaturesQueue() {
        return durableQueue(FEATURES_QUEUE);
    }

    @Bean
    Queue screeningLlmQueue() {
        return durableQueue(LLM_QUEUE);
    }

    @Bean
    Queue screeningResultQueue() {
        return durableQueue(RESULT_QUEUE);
    }

    @Bean
    Queue screeningStatusQueue() {
        return durableQueue(STATUS_QUEUE);
    }

    @Bean
    Queue pdfGenerateQueue() {
        return durableQueue(PDF_QUEUE);
    }

    @Bean
    Queue screeningDeadLetterQueue() {
        return QueueBuilder.durable("alz.screening.dlq").build();
    }

    @Bean
    Binding transcriptionBinding(
            @Qualifier("screeningTranscriptionQueue") Queue screeningTranscriptionQueue,
            @Qualifier("screeningExchange") TopicExchange screeningExchange) {
        return BindingBuilder.bind(screeningTranscriptionQueue).to(screeningExchange)
                .with("screening.requested.v1");
    }

    @Bean
    Binding featuresBinding(
            @Qualifier("screeningFeaturesQueue") Queue screeningFeaturesQueue,
            @Qualifier("screeningExchange") TopicExchange screeningExchange) {
        return BindingBuilder.bind(screeningFeaturesQueue).to(screeningExchange)
                .with("screening.transcription.completed.v1");
    }

    @Bean
    Binding llmBinding(
            @Qualifier("screeningLlmQueue") Queue screeningLlmQueue,
            @Qualifier("screeningExchange") TopicExchange screeningExchange) {
        return BindingBuilder.bind(screeningLlmQueue).to(screeningExchange)
                .with("screening.features.completed.v1");
    }

    @Bean
    Binding resultBinding(
            @Qualifier("screeningResultQueue") Queue screeningResultQueue,
            @Qualifier("screeningExchange") TopicExchange screeningExchange) {
        return BindingBuilder.bind(screeningResultQueue).to(screeningExchange)
                .with("screening.analysis.completed.v1");
    }

    @Bean
    Binding statusScreeningBinding(
            @Qualifier("screeningStatusQueue") Queue screeningStatusQueue,
            @Qualifier("screeningExchange") TopicExchange screeningExchange) {
        return BindingBuilder.bind(screeningStatusQueue).to(screeningExchange).with("screening.#");
    }

    @Bean
    Binding statusPdfBinding(
            @Qualifier("screeningStatusQueue") Queue screeningStatusQueue,
            @Qualifier("screeningExchange") TopicExchange screeningExchange) {
        return BindingBuilder.bind(screeningStatusQueue).to(screeningExchange).with("pdf.#");
    }

    @Bean
    Binding pdfBinding(
            @Qualifier("pdfGenerateQueue") Queue pdfGenerateQueue,
            @Qualifier("screeningExchange") TopicExchange screeningExchange) {
        return BindingBuilder.bind(pdfGenerateQueue).to(screeningExchange).with("pdf.requested.v1");
    }

    @Bean
    Binding deadLetterBinding(
            @Qualifier("screeningDeadLetterQueue") Queue screeningDeadLetterQueue,
            @Qualifier("screeningDeadLetterExchange") TopicExchange screeningDeadLetterExchange) {
        return BindingBuilder.bind(screeningDeadLetterQueue).to(screeningDeadLetterExchange).with("#");
    }

    @Bean
    Jackson2JsonMessageConverter screeningMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean("screeningResultExecutor")
    TaskExecutor screeningResultExecutor(
            @Value("${app.screening.async.result-concurrency:2}") int concurrency,
            @Value("${app.screening.async.listener-queue-capacity:20}") int queueCapacity) {
        return executor("screening-result-", concurrency, queueCapacity);
    }

    @Bean("screeningStatusExecutor")
    TaskExecutor screeningStatusExecutor(
            @Value("${app.screening.async.status-concurrency:2}") int concurrency,
            @Value("${app.screening.async.listener-queue-capacity:20}") int queueCapacity) {
        return executor("screening-status-", concurrency, queueCapacity);
    }

    @Bean("screeningPdfExecutor")
    TaskExecutor screeningPdfExecutor(
            @Value("${app.screening.async.pdf-concurrency:1}") int concurrency,
            @Value("${app.screening.async.listener-queue-capacity:20}") int queueCapacity) {
        return executor("screening-pdf-", concurrency, queueCapacity);
    }

    @Bean
    RetryOperationsInterceptor screeningListenerRetry() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 3.0, 10000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    @Bean("screeningResultContainerFactory")
    SimpleRabbitListenerContainerFactory screeningResultContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RetryOperationsInterceptor screeningListenerRetry,
            @Qualifier("screeningResultExecutor") TaskExecutor executor,
            @Value("${app.screening.async.result-concurrency:2}") int concurrency,
            @Value("${spring.rabbitmq.listener.simple.prefetch:1}") int prefetch) {
        return listenerFactory(connectionFactory, converter, screeningListenerRetry,
                executor, concurrency, prefetch);
    }

    @Bean("screeningStatusContainerFactory")
    SimpleRabbitListenerContainerFactory screeningStatusContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RetryOperationsInterceptor screeningListenerRetry,
            @Qualifier("screeningStatusExecutor") TaskExecutor executor,
            @Value("${app.screening.async.status-concurrency:2}") int concurrency,
            @Value("${spring.rabbitmq.listener.simple.prefetch:1}") int prefetch) {
        return listenerFactory(connectionFactory, converter, screeningListenerRetry,
                executor, concurrency, prefetch);
    }

    @Bean("screeningPdfContainerFactory")
    SimpleRabbitListenerContainerFactory screeningPdfContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RetryOperationsInterceptor screeningListenerRetry,
            @Qualifier("screeningPdfExecutor") TaskExecutor executor,
            @Value("${app.screening.async.pdf-concurrency:1}") int concurrency,
            @Value("${spring.rabbitmq.listener.simple.prefetch:1}") int prefetch) {
        return listenerFactory(connectionFactory, converter, screeningListenerRetry,
                executor, concurrency, prefetch);
    }

    private Queue durableQueue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", name + ".dead")
                .build();
    }

    private TaskExecutor executor(String prefix, int configuredConcurrency, int queueCapacity) {
        int concurrency = Math.max(1, configuredConcurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private SimpleRabbitListenerContainerFactory listenerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RetryOperationsInterceptor retry,
            TaskExecutor executor,
            int configuredConcurrency,
            int configuredPrefetch) {
        int concurrency = Math.max(1, configuredConcurrency);
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(Math.max(1, configuredPrefetch));
        factory.setTaskExecutor(executor);
        factory.setAdviceChain(retry);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
