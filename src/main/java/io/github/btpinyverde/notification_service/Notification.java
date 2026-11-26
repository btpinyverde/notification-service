package io.github.btpinyverde.notification_service;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;


record Notification(UUID id, Message message, Status status, int attempts,
                           String lastError, Instant createdAt, Instant updatedAt) {
    public enum Channel { LOG, SERVICE }
    public enum Priority { LOW, MEDIUM, HIGH }
    public enum Status { PENDING, PROCESSING, SENT, FAILED }

    public record Message(String recipient, Channel channel, String subject, String body,
                          Priority priority, Map<String, String> metadata) {}
}
