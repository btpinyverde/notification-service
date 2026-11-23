package io.github.btpinyverde.notification_service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/notifications")
class NotificationApi {
    private static final String WITHOUT_NUL = "[^\\x00]*";
    private static final String NUL_MESSAGE = "no puede contener el carácter nulo (U+0000)";
    private static final Logger LOG = LoggerFactory.getLogger(NotificationApi.class);
    private final Notifications notifications;
    private final ServiceTargets targets;

    NotificationApi(Notifications notifications, ServiceTargets targets) {
        this.notifications = notifications;
        this.targets = targets;
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    ResponseEntity<Accepted> create(@Valid @RequestBody Create request) {
        if (request.channel() == Notification.Channel.SERVICE && !targets.allows(request.recipient())) {
            throw new ResponseStatusException(BAD_REQUEST, "recipient debe ser una URL SERVICE habilitada");
        }
        var message = new Notification.Message(request.recipient(), request.channel(), request.subject(),
                request.body(), request.priority(), request.metadata() == null ? Map.of() : Map.copyOf(request.metadata()));
        UUID id = notifications.create(message);
        LOG.atInfo().addKeyValue("event", "notification.accepted")
                .addKeyValue("notificationId", id).addKeyValue("channel", message.channel()).log("Notificación aceptada");
        return ResponseEntity.accepted().location(URI.create("/notifications/" + id))
                .body(new Accepted(id, Notification.Status.PENDING));
    }

    @GetMapping("/{id}")
    View get(@PathVariable UUID id) {
        return notifications.find(id)
                .map(n -> new View(n.id(), n.status(), n.attempts(), n.lastError(), n.createdAt(), n.updatedAt()))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No existe esa notificación"));
    }

    record Create(
            @NotBlank @Size(max = 512) @Pattern(regexp = WITHOUT_NUL, message = NUL_MESSAGE) String recipient,
            @NotNull Notification.Channel channel,
            @NotBlank @Size(max = 200) @Pattern(regexp = WITHOUT_NUL, message = NUL_MESSAGE) String subject,
            @NotBlank @Size(max = 10000) @Pattern(regexp = WITHOUT_NUL, message = NUL_MESSAGE) String body,
            @NotNull Notification.Priority priority,
            @Size(max = 32) Map<
                    @NotBlank @Size(max = 64) @Pattern(regexp = WITHOUT_NUL, message = NUL_MESSAGE) String,
                    @NotNull @Size(max = 512) @Pattern(regexp = WITHOUT_NUL, message = NUL_MESSAGE) String> metadata) {}

    record Accepted(UUID id, Notification.Status status) {}
    record View(UUID id, Notification.Status status, int attempts, String lastError,
                Instant createdAt, Instant updatedAt) {}
}