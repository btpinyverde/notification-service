package io.github.btpinyverde.notification_service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository 
class Notifications {
    
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    Notifications(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    UUID create(Notification.Message message) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO notifications (id, recipient, channel, subject, body, priority, metadata)
            VALUES (?,?,?,?,?,?, cast(? AS jsonb))
            """, id, message.recipient(), message.channel().name(), message.subject(),
            message.body(), message.priority().name(), writeMetadata(message.metadata()));
       
        return id;
    }

    Optional<Notification> find(UUID id) {
        return jdbc.query("SELECT * FROM notifications WHERE id = ?", this::read, id)
            .stream()
            .findFirst();
        }
    
        Notification read(ResultSet row, int index) throws SQLException {
            Notification.Message message = new Notification.Message(
                row.getString("recipient"),
                Notification.Channel.valueOf(row.getString("channel")),
                row.getString("subject"),
                row.getString("body"),
                Notification.Priority.valueOf(row.getString("priority")),
                readMetadata(row.getString("metadata"))
            );
            return new Notification(
                row.getObject("id", UUID.class),
                message,
                Notification.Status.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
        }


    private Object writeMetadata(Map<String,String> metadata) {
        try {
            return json.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("No se pudo serializar metadata", exception);
        }
        
    }

    private Map<String,String> readMetadata(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Metadata persistida inválida", exception);
        }
    }

}
