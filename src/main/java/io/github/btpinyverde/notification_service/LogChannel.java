package io.github.btpinyverde.notification_service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LogChannel implements NotificationChannel {
    private static final Logger LOG = LoggerFactory.getLogger("notification.delivery");

    @Override
    public Notification.Channel type() {
        return Notification.Channel.LOG;
    }

    @Override
    public void send(Notification notification) {
        var message = notification.message();
        LOG.atInfo().addKeyValue("event", "notification.log_delivery")
                .addKeyValue("notificationId", notification.id())
                .addKeyValue("recipient", message.recipient())
                .addKeyValue("subject", message.subject())
                .addKeyValue("body", message.body())
                .addKeyValue("priority", message.priority())
                .addKeyValue("metadata", message.metadata())
                .log("Notificación entregada al canal LOG");
    }
}
