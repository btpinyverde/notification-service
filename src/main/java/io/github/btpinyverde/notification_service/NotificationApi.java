package io.github.btpinyverde.notification_service;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import static org.springframework.http.HttpStatus.NOT_FOUND;


@RestController 
@RequestMapping ("/notifications")
class NotificationApi {
    private final Notifications notifications;

    NotificationApi(Notifications notifications) {
        this.notifications = notifications;
    }


@PostMapping
@ResponseStatus (org.springframework.http.HttpStatus.ACCEPTED)
ResponseEntity<Accepted> create(@Valid @RequestBody Create request) {
    var message = new Notification.Message(request.recipient(), request.channel(), request.subject(), request.body(), request.priority(), 
            request.metadata() == null ? Map.of() : Map.copyOf(request.metadata()));
    UUID id = notifications.create(message);
    return ResponseEntity.accepted().location(URI.create("/notifications/" + id)).body(new Accepted(id, Notification.Status.PENDING));

}

@GetMapping("/{id}")
View get(@PathVariable UUID id) {
    return notifications.find(id).map(n -> new View(n.id(), n.status(), n.createdAt()))
    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "no existe esa notificacion"));
}



    record Create(@NotBlank @Size(max = 512) String recipient,
                  @NotNull Notification.Channel channel,
                  @NotBlank @Size(max = 200) String subject,
                  @NotBlank @Size(max = 10000) String body,
                  @NotNull Notification.Priority priority,
                  @Size(max = 32) Map<@NotBlank @Size(max = 64) String, @NotNull @Size(max = 512) String> metadata) {}
    record Accepted(UUID id, Notification.Status status) {}
    record View(UUID id, Notification.Status status, Instant createdAt) {}



    
}
