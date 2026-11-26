ALTER TABLE notifications
    DROP CONSTRAINT notifications_channel_check,
    ADD CONSTRAINT notifications_channel_check CHECK (channel IN ('LOG', 'SERVICE'));
