DROP INDEX idx_outbox_polling;

CREATE INDEX idx_outbox_polling
    ON outbox_events (available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'PROCESSING');

CREATE TABLE consumed_outbox_events (
    consumer_name VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL REFERENCES outbox_events (id),
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id)
);
