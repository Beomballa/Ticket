package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminCompensationSummary(
        UUID refundAttemptId,
        Long reservationId,
        UUID paymentAttemptId,
        BigDecimal amount,
        RefundAttemptStatus status,
        int attempts,
        Instant requestedAt,
        Instant resolvedAt,
        Instant nextReconciliationAt,
        Instant reconciliationLeaseUntil,
        String lastError
) {
}
