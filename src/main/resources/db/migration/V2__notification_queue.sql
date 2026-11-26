ALTER TABLE notifications
    DROP CONSTRAINT notifications_status_check,
    ADD CONSTRAINT notifications_status_check CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN claim_token uuid,
    ADD COLUMN last_error varchar(200),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT notifications_reservation_check CHECK (
        (status = 'PROCESSING' AND lease_until IS NOT NULL AND claim_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_until IS NULL AND claim_token IS NULL)
    );

CREATE INDEX notifications_due ON notifications (next_attempt_at, created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX notifications_expired ON notifications (lease_until)
    WHERE status = 'PROCESSING';
