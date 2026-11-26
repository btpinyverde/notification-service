package io.github.btpinyverde.notification_service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
class Dispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

    private final NotificationQueue queue;
    private final MeterRegistry metrics;
    private final Map<Notification.Channel, NotificationChannel> channels;
    private final RetryPolicy retry = new RetryPolicy();

    Dispatcher(NotificationQueue queue, List<NotificationChannel> handlers, MeterRegistry metrics) {
        this.queue = queue;
        this.metrics = metrics;
        channels = new EnumMap<>(Notification.Channel.class);
        for (NotificationChannel handler : handlers) {
            if (channels.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("Canal duplicado: " + handler.type());
            }
        }
    }

    void poll() {
        queue.claim().ifPresent(this::dispatch);
    }

    private void dispatch(NotificationQueue.Claim claim) {
        Notification n = claim.notification();
        lifecycle(n, "notification.processing", Notification.Status.PROCESSING, null);

        try {
            NotificationChannel channel = channels.get(n.message().channel());
            if (channel == null) {
                throw new NotificationChannel.DispatchFailure("CHANNEL_UNAVAILABLE");
            }
            channel.send(n);
        } catch (NotificationChannel.DispatchFailure failure) {
            recordFailure(claim, failure.getMessage());
            return;
        } catch (RuntimeException failure) {
            recordFailure(claim, "UNEXPECTED_DISPATCH_ERROR");
            return;
        }

        if (queue.sent(claim)) {
            recordOutcome("sent");
            lifecycle(n, "notification.sent", Notification.Status.SENT, null);
        } else {
            recordOutcome("lease_lost");
            lifecycle(n, "notification.claim_lost", Notification.Status.PROCESSING, null);
        }
    }

    private void recordFailure(NotificationQueue.Claim claim, String reason) {
        Notification n = claim.notification();
        RetryPolicy.Outcome outcome = retry.afterFailure(n.attempts());
        if (queue.failedAttempt(claim, outcome, reason)) {
            boolean terminal = outcome.status() == Notification.Status.FAILED;
            recordOutcome(terminal ? "failed" : "retry");
            String event = terminal ? "notification.failed" : "notification.retry_scheduled";
            lifecycle(n, event, outcome.status(), reason);
        } else {
            recordOutcome("lease_lost");
            lifecycle(n, "notification.claim_lost", Notification.Status.PROCESSING, reason);
        }
    }

    void recover() {
        for (NotificationQueue.Recovered n : queue.recoverExpired(RetryPolicy.MAX_ATTEMPTS)) {
            recordOutcome("lease_expired");
            LOG.atWarn().addKeyValue("event", "notification.lease_expired")
                    .addKeyValue("notificationId", n.id()).addKeyValue("attempts", n.attempts())
                    .addKeyValue("status", n.status()).log("Reserva de despacho vencida");
        }
    }

    private void recordOutcome(String outcome) {
        metrics.counter("notifications.dispatch.outcomes", "outcome", outcome).increment();
    }

    private void lifecycle(Notification n, String event, Notification.Status status, String error) {
        var entry = error == null ? LOG.atInfo() : LOG.atWarn().addKeyValue("errorCode", error);
        entry.addKeyValue("event", event).addKeyValue("notificationId", n.id())
                .addKeyValue("channel", n.message().channel()).addKeyValue("attempts", n.attempts())
                .addKeyValue("status", status).log("Estado del despacho");
    }
}
