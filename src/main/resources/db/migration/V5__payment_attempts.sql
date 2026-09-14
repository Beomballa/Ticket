CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservations (id),
    gateway_idempotency_key VARCHAR(120) NOT NULL,
    payment_token_fingerprint VARCHAR(64) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    gateway_reference VARCHAR(120),
    last_error TEXT,
    requested_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_payment_attempts_gateway_key UNIQUE (gateway_idempotency_key),
    CONSTRAINT ck_payment_attempts_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_attempts_status CHECK (
        status IN ('REQUESTED', 'APPROVED', 'DECLINED', 'UNKNOWN')
    )
);

CREATE INDEX idx_payment_attempts_reservation
    ON payment_attempts (reservation_id, requested_at DESC, id DESC);

CREATE INDEX idx_payment_attempts_reconciliation
    ON payment_attempts (requested_at, id)
    WHERE status = 'UNKNOWN';
