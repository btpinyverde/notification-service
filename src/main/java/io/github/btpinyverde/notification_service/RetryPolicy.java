package io.github.btpinyverde.notification_service;

import java.time.Duration;

import static io.github.btpinyverde.notification_service.Notification.Status.FAILED;
import static io.github.btpinyverde.notification_service.Notification.Status.PENDING;

final class RetryPolicy {
    static final int MAX_ATTEMPTS = 3;

    Outcome afterFailure(int attempts) {
        if (attempts >= MAX_ATTEMPTS) {
            return new Outcome(FAILED, Duration.ZERO);
        }
        return new Outcome(PENDING, Duration.ofSeconds(1L << (attempts - 1)));
    }

    record Outcome(Notification.Status status, Duration delay) {}
}

