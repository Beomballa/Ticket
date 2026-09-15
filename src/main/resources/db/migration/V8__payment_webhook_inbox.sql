CREATE TABLE payment_webhook_inbox (
    id UUID PRIMARY KEY,
    provider_event_id VARCHAR(120) NOT NULL UNIQUE,
    payload_hash VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    gateway_idempotency_key VARCHAR(120) NOT NULL,
    result VARCHAR(20) NOT NULL,
    gateway_reference VARCHAR(120),
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    processing_lease_until TIMESTAMPTZ,
    last_error TEXT,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_payment_webhook_inbox_status
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_payment_webhook_inbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_payment_webhook_inbox_operations
    ON payment_webhook_inbox (received_at DESC, id DESC);

CREATE INDEX idx_payment_webhook_inbox_recovery
    ON payment_webhook_inbox (status, processing_lease_until, received_at)
    WHERE status IN ('RECEIVED', 'PROCESSING', 'FAILED');
