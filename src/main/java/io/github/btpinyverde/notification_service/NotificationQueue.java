package io.github.btpinyverde.notification_service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface NotificationQueue {
    Optional<Claim> claim();
    boolean sent(Claim claim);
    boolean failedAttempt(Claim claim, RetryPolicy.Outcome outcome, String reason);
    List<Recovered> recoverExpired(int maxAttempts);

    record Claim(Notification notification, UUID token) {}
    record Recovered(UUID id, Notification.Status status, int attempts) {}
}
