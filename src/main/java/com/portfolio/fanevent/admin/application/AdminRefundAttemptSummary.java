package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminRefundAttemptSummary(
        UUID refundAttemptId,
        Long reservationId,
        UUID paymentAttemptId,
        BigDecimal amount,
        RefundAttemptStatus status,
        String gatewayRefundReference,
        String lastError,
        Instant requestedAt,
        Instant resolvedAt
) {
}
