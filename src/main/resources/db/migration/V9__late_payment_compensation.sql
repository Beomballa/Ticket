ALTER TABLE refund_attempts
    ADD COLUMN purpose VARCHAR(40) NOT NULL DEFAULT 'RESERVATION_CANCELLATION';

ALTER TABLE refund_attempts
    ALTER COLUMN purpose DROP DEFAULT,
    ADD CONSTRAINT ck_refund_attempts_purpose
        CHECK (purpose IN ('RESERVATION_CANCELLATION', 'LATE_PAYMENT_COMPENSATION'));

CREATE INDEX idx_refund_attempts_compensation
    ON refund_attempts (next_reconciliation_at, id)
    WHERE purpose = 'LATE_PAYMENT_COMPENSATION'
      AND status IN ('REQUESTED', 'UNKNOWN');
