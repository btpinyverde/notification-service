package io.github.btpinyverde.notification_service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static io.github.btpinyverde.notification_service.Notification.Channel.LOG;
import static io.github.btpinyverde.notification_service.Notification.Priority.MEDIUM;
import static io.github.btpinyverde.notification_service.Notification.Status.PROCESSING;

class DispatcherTest {
    private final NotificationQueue queue = mock(NotificationQueue.class);
    private final NotificationChannel channel = mock(NotificationChannel.class);
    private Dispatcher dispatcher;

    @BeforeEach
    void registerChannel() {
        when(channel.type()).thenReturn(LOG);
        dispatcher = new Dispatcher(queue, List.of(channel));
        clearInvocations(channel);
    }

    @Test
    void doesNothingWhenQueueIsEmpty() {
        when(queue.claim()).thenReturn(Optional.empty());

        dispatcher.poll();

        verify(queue).claim();
        verifyNoMoreInteractions(queue);
        verifyNoInteractions(channel);
    }

    @Test
    void recordsSuccessOnlyAfterDelivery() throws Exception {
        var claim = claim(1);
        when(queue.claim()).thenReturn(Optional.of(claim));
        when(queue.sent(claim)).thenReturn(true);

        dispatcher.poll();

        var order = inOrder(queue, channel);
        order.verify(queue).claim();
        order.verify(channel).send(claim.notification());
        order.verify(queue).sent(claim);
        verifyNoMoreInteractions(queue, channel);
    }

    @ParameterizedTest
    @CsvSource({"1,PENDING,1", "2,PENDING,2", "3,FAILED,0"})
    void recordsFailuresWithBoundedBackoff(int attempt, Notification.Status status, int delay)
            throws Exception {
        var claim = claim(attempt);
        when(queue.claim()).thenReturn(Optional.of(claim));
        doThrow(new NotificationChannel.DispatchFailure("DISPATCH_FAILED")).when(channel).send(claim.notification());

        dispatcher.poll();

        var outcome = new RetryPolicy.Outcome(status, Duration.ofSeconds(delay));
        verify(queue).failedAttempt(claim, outcome, "DISPATCH_FAILED");
        verify(queue, never()).sent(any());
        assertThat(new RetryPolicy().afterFailure(attempt)).isEqualTo(outcome);
    }

    private NotificationQueue.Claim claim(int attempt) {
        var message = new Notification.Message("demo", LOG, "test", "body", MEDIUM, Map.of());
        var n = new Notification(UUID.randomUUID(), message, PROCESSING, attempt, null,
                Instant.EPOCH, Instant.EPOCH);
        return new NotificationQueue.Claim(n, UUID.randomUUID());
    }
}
