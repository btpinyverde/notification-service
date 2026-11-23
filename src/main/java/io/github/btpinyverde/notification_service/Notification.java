package io.github.btpinyverde.notification_service;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;


public record Notification(UUID id, Message message, Status status, Instant createdAt) {
    enum Channel { LOG }
    enum Priority { LOW, MEDIUM, HIGH }
    enum Status { PENDING }

    record Message(String recipient, Channel channel, String subject, String body, 
        Priority priority, Map<String, String> metadata) {}

}
