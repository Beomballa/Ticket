CREATE TABLE refund_attempts (
    id UUID PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservations (id),
    payment_attempt_id UUID NOT NULL REFERENCES payment_attempts (id),
    gateway_idempotency_key VARCHAR(120) NOT NULL,
    gateway_payment_reference VARCHAR(120) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    gateway_refund_reference VARCHAR(120),
    last_error TEXT,
    requested_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_refund_attempts_reservation UNIQUE (reservation_id),
    CONSTRAINT uk_refund_attempts_gateway_key UNIQUE (gateway_idempotency_key),
    CONSTRAINT ck_refund_attempts_amount CHECK (amount >= 0),
    CONSTRAINT ck_refund_attempts_status CHECK (
        status IN ('REQUESTED', 'SUCCEEDED', 'DECLINED', 'UNKNOWN')
    )
);

CREATE INDEX idx_refund_attempts_reconciliation
    ON refund_attempts (requested_at, id)
    WHERE status = 'UNKNOWN';
