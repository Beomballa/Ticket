ALTER TABLE payment_attempts
    ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_reconciliation_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_lease_until TIMESTAMPTZ,
    ADD COLUMN last_reconciliation_at TIMESTAMPTZ;

ALTER TABLE payment_attempts
    ADD CONSTRAINT ck_payment_attempts_reconciliation_attempts
        CHECK (reconciliation_attempts >= 0);

UPDATE payment_attempts
SET next_reconciliation_at = requested_at
WHERE status = 'UNKNOWN';

ALTER TABLE refund_attempts
    ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_reconciliation_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_lease_until TIMESTAMPTZ,
    ADD COLUMN last_reconciliation_at TIMESTAMPTZ;

ALTER TABLE refund_attempts
    ADD CONSTRAINT ck_refund_attempts_reconciliation_attempts
        CHECK (reconciliation_attempts >= 0);

UPDATE refund_attempts
SET next_reconciliation_at = requested_at
WHERE status = 'UNKNOWN';

CREATE INDEX idx_payment_attempts_auto_reconciliation
    ON payment_attempts (next_reconciliation_at, id)
    WHERE status = 'UNKNOWN';

CREATE INDEX idx_refund_attempts_auto_reconciliation
    ON refund_attempts (next_reconciliation_at, id)
    WHERE status = 'UNKNOWN';
