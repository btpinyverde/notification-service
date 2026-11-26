package io.github.btpinyverde.notification_service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Notification(UUID id, Message message, Status status, Instant createdAt) {
    enum Channel { LOG }
    enum Priority { LOW, MEDIUM, HIGH }
    enum Status { PENDING }


    record Message(String recipient, 
        Channel chanel, 
        String subject, 
        String body, 
        Priority priority, 
        Map <String, String> metadata
    ) {}
    
}
