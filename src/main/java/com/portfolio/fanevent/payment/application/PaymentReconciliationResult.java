package com.portfolio.fanevent.payment.application;

public record PaymentReconciliationResult(
        PaymentGatewayResult result,
        String gatewayReference
) {
}
