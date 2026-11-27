package io.github.btpinyverde.notification_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HttpChannelTest {
    @Test
    void timesOutWhenReceiverAcceptsRequestButDoesNotRespond() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();

        try {
            receiver.setExecutor(executor);
            receiver.createContext("/slow", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    received.countDown();
                    try {
                        release.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            receiver.start();

            String target = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/slow";
            var channel = new HttpChannel(new ObjectMapper(), new ServiceTargets(target));
            var notification = new Notification(UUID.randomUUID(),
                    new Notification.Message(target, Notification.Channel.SERVICE, "test", "body",
                            Notification.Priority.MEDIUM, Map.of()),
                    Notification.Status.PROCESSING, 1, null, Instant.now(), Instant.now());

            var failure = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThrows(NotificationChannel.DispatchFailure.class,
                            () -> channel.send(notification)));

            assertThat(received.getCount()).isZero();
            assertThat(failure.getMessage()).isEqualTo("HTTP_TIMEOUT");
        } finally {
            release.countDown();
            receiver.stop(0);
            executor.shutdownNow();
        }
    }
}