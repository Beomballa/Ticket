package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminPaymentAttemptSummary(
        UUID paymentAttemptId,
        Long reservationId,
        BigDecimal amount,
        PaymentAttemptStatus status,
        String gatewayReference,
        String lastError,
        Instant requestedAt,
        Instant resolvedAt
) {
}
