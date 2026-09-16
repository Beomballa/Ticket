CREATE UNIQUE INDEX uk_payment_attempts_unresolved_reservation
    ON payment_attempts (reservation_id)
    WHERE status IN ('REQUESTED', 'UNKNOWN');
