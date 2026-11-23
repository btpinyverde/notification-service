CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    recipient varchar(512) NOT NULL,
    channel varchar(16) NOT NULL CONSTRAINT notifications_channel_check CHECK (channel IN ('LOG')),
    subject varchar(200) NOT NULL,
    body varchar(10000) NOT NULL,
    priority varchar(16) NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT notifications_status_check CHECK (status IN ('PENDING')),
    created_at timestamptz NOT NULL DEFAULT now()
);
