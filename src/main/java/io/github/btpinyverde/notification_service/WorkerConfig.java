package io.github.btpinyverde.notification_service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "notifications.worker.enabled", havingValue = "true")
class WorkerConfig implements SchedulingConfigurer {
    private final Dispatcher dispatcher;
    private final int concurrency;
    private final Duration pollInterval;

    WorkerConfig(Dispatcher dispatcher, @Value("${notifications.worker.concurrency}") int concurrency,
                 @Value("${notifications.worker.poll-ms}") long pollMs) {
        if (concurrency < 1 || concurrency > 16 || pollMs < 50) {
            throw new IllegalArgumentException("Configuración del worker fuera de rango");
        }
        this.dispatcher = dispatcher;
        this.concurrency = concurrency;
        this.pollInterval = Duration.ofMillis(pollMs);
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("notifications-");
        scheduler.setPoolSize(concurrency + 1);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar tasks) {
        for (int lane = 0; lane < concurrency; lane++) {
            tasks.addFixedDelayTask(dispatcher::poll, pollInterval);
        }
        tasks.addFixedDelayTask(dispatcher::recover, Duration.ofSeconds(1));
    }
}
