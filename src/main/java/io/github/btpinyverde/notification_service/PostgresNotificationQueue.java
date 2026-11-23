package io.github.btpinyverde.notification_service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class PostgresNotificationQueue implements NotificationQueue {
    private final JdbcTemplate jdbc;
    private final Notifications notifications;

    PostgresNotificationQueue(JdbcTemplate jdbc, Notifications notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @Override
    public Optional<Claim> claim() {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM notifications
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY CASE priority
                        WHEN 'HIGH' THEN 1
                        WHEN 'MEDIUM' THEN 2
                        ELSE 3
                    END, created_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE notifications AS n
                SET status = 'PROCESSING', attempts = attempts + 1, updated_at = now(),
                    claim_token = ?, lease_until = now() + interval '30 seconds'
                FROM candidate
                WHERE n.id = candidate.id
                RETURNING n.*
                """, (row, index) -> new Claim(notifications.read(row, index),
                        row.getObject("claim_token", UUID.class)), UUID.randomUUID()).stream().findFirst();
    }

    @Override
    public boolean sent(Claim claim) {
        return finish(claim, Notification.Status.SENT, 0, null);
    }

    @Override
    public boolean failedAttempt(Claim claim, RetryPolicy.Outcome outcome, String reason) {
        return finish(claim, outcome.status(), outcome.delay().toMillis(), reason);
    }

    private boolean finish(Claim claim, Notification.Status status, long delayMs, String reason) {
        return jdbc.update("""
                UPDATE notifications
                SET status = ?, next_attempt_at = now() + (? * interval '1 millisecond'),
                    updated_at = now(), last_error = ?, lease_until = NULL, claim_token = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ? AND lease_until > now()
                """, status.name(), delayMs, reason, claim.notification().id(), claim.token()) == 1;
    }

    @Override
    public List<Recovered> recoverExpired(int maxAttempts) {
        return jdbc.query("""
                WITH expired AS (
                    SELECT id
                    FROM notifications
                    WHERE status = 'PROCESSING' AND lease_until <= now()
                    ORDER BY lease_until
                    LIMIT 50 FOR UPDATE SKIP LOCKED
                )
                UPDATE notifications AS n
                SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = now(), updated_at = now(), last_error = 'LEASE_EXPIRED',
                    lease_until = NULL, claim_token = NULL
                FROM expired
                WHERE n.id = expired.id
                RETURNING n.id, n.status, n.attempts
                """, (row, index) -> new Recovered(row.getObject("id", UUID.class),
                        Notification.Status.valueOf(row.getString("status")), row.getInt("attempts")), maxAttempts);
    }
}