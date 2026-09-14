package com.portfolio.fanevent.payment.application;

import java.math.BigDecimal;

public interface PaymentGateway {

    PaymentAuthorization authorize(
            Long reservationId,
            BigDecimal amount,
            String paymentToken,
            String gatewayIdempotencyKey
    );

    PaymentReconciliationResult getAuthorizationResult(String gatewayIdempotencyKey);

    RefundResult refund(
            Long reservationId,
            BigDecimal amount,
            String gatewayPaymentReference,
            String gatewayIdempotencyKey
    );

    RefundResult getRefundResult(String gatewayIdempotencyKey);
}
