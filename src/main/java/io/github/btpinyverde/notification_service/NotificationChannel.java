package io.github.btpinyverde.notification_service;

interface NotificationChannel {
    Notification.Channel type();
    void send(Notification notification) throws DispatchFailure;

    class DispatchFailure extends Exception {
        DispatchFailure(String reason) {
            super(reason);
        }
    }
}
