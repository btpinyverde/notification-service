package io.github.btpinyverde.notification_service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.btpinyverde.notification_service.Notification.Priority.HIGH;
import static io.github.btpinyverde.notification_service.Notification.Priority.LOW;
import static io.github.btpinyverde.notification_service.Notification.Priority.MEDIUM;

@SpringBootTest(properties = {"notifications.worker.enabled=false", "notifications.api-key=queue-test-key"})
class QueueConcurrencyIntegrationTest extends PostgresTestSupport {
    @Autowired NotificationQueue queue;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void resetQueue() {
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void concurrentConsumersCanReserveDifferentJobs() throws Exception {
        UUID first = enqueue(HIGH, 120, -10);
        UUID second = enqueue(LOW, 240, -20);

        whileFirstHasUncommittedClaim(first, () -> {
            var reserved = new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '3s'");
                return queue.claim().orElseThrow();
            });
            assertThat(reserved.notification().id()).isEqualTo(second);
        });

        assertThat(jdbc.queryForList("""
                SELECT id FROM notifications WHERE status = 'PROCESSING' AND attempts = 1
                """, UUID.class)).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void secondConsumerCannotReserveTheOnlyJobWhileFirstTransactionIsOpen() throws Exception {
        UUID job = enqueue(HIGH, 120, -10);

        whileFirstHasUncommittedClaim(job, () -> {
            var reserved = new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '3s'");
                return queue.claim();
            });
            assertThat(reserved).isEmpty();
        });

        assertThat(queue.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempts FROM notifications WHERE id = ?", Integer.class, job))
                .isEqualTo(1);
    }

    @Test
    void claimsEligibleJobsByPriorityThenCreationTime() {
        UUID low = enqueue(LOW, 3600, -300);
        UUID medium = enqueue(MEDIUM, 1800, -150);
        UUID newerHigh = enqueue(HIGH, 120, -100);
        UUID olderHigh = enqueue(HIGH, 900, -5);
        enqueue(HIGH, 2000, 3600);

        for (UUID expected : new UUID[]{olderHigh, newerHigh, medium, low}) {
            assertThat(queue.claim().orElseThrow().notification().id()).isEqualTo(expected);
        }
        assertThat(queue.claim()).isEmpty();
    }

    private void whileFirstHasUncommittedClaim(UUID expected, Runnable secondConsumer) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var firstClaim = new CompletableFuture<UUID>();
        var release = new CountDownLatch(1);
        var firstWorker = executor.submit(() -> {
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    var claim = queue.claim().orElseThrow();
                    firstClaim.complete(claim.notification().id());
                    awaitRelease(release);
                });
            } catch (RuntimeException | Error failure) {
                firstClaim.completeExceptionally(failure);
                throw failure;
            }
        });

        try {
            assertThat(firstClaim.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
            secondConsumer.run();
            release.countDown();
            firstWorker.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("El segundo consumidor no terminó a tiempo");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Se interrumpió la primera reserva", exception);
        }
    }

    private UUID enqueue(Notification.Priority priority, int ageSeconds, int availableInSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications
                    (id, recipient, channel, subject, body, priority, metadata, created_at, next_attempt_at)
                VALUES (?, 'diagnostics', 'LOG', 'queue test', 'test', ?, '{}',
                        now() - (? * interval '1 second'), now() + (? * interval '1 second'))
                """, id, priority.name(), ageSeconds, availableInSeconds);
        return id;
    }
}
