package io.github.btpinyverde.notification_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import static io.github.btpinyverde.notification_service.Notification.Channel.LOG;
import static io.github.btpinyverde.notification_service.Notification.Priority.HIGH;
import static io.github.btpinyverde.notification_service.Notification.Status.PENDING;

@SpringBootTest(properties = {"notifications.api-key=test-key", "notifications.worker.enabled=false"})
class PersistenceIntegrationTest extends PostgresTestSupport {
    @Autowired Notifications notifications;

    @Test
    void savesTheMessageAndReadsItById() {
        var message = new Notification.Message(
            "demo",
            LOG,
            "prueba",
            "mensaje",
            HIGH,
            Map.of("source", "test"));
        var id = notifications.create(message);

        var saved = notifications.find(id).orElseThrow();
        assertThat(saved.id()).isEqualTo(id);
        assertThat(saved.message()).isEqualTo(message);
        assertThat(saved.status()).isEqualTo(PENDING);
        assertThat(saved.createdAt()).isNotNull();
    }
    
}
