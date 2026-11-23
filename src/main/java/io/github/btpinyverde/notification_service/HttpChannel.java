package io.github.btpinyverde.notification_service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
class HttpChannel implements NotificationChannel {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper json;
    private final ServiceTargets targets;

    HttpChannel(ObjectMapper json, ServiceTargets targets) {
        this.json = json;
        this.targets = targets;
    }

    @Override
    public Notification.Channel type() {
        return Notification.Channel.SERVICE;
    }

    @Override
    public void send(Notification notification) throws DispatchFailure {
        String recipient = notification.message().recipient();
        if (!targets.allows(recipient)) {
            throw new DispatchFailure("TARGET_NOT_ALLOWED");
        }

        String payload;
        try {
            payload = json.writeValueAsString(new Payload(notification.id(), notification.message()));
        } catch (JsonProcessingException exception) {
            throw new DispatchFailure("PAYLOAD_SERIALIZATION");
        }

        var request = HttpRequest.newBuilder(URI.create(recipient))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", notification.id().toString())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        int status;
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            response.body().close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DispatchFailure("HTTP_INTERRUPTED");
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new DispatchFailure("HTTP_TIMEOUT");
        } catch (IOException exception) {
            throw new DispatchFailure("HTTP_IO_ERROR");
        }
        if (status < 200 || status >= 300) {
            throw new DispatchFailure("HTTP_" + status);
        }
    }

    record Payload(UUID id, Notification.Message message) {}
}
