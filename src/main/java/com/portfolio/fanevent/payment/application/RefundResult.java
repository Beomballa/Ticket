package com.portfolio.fanevent.payment.application;

public record RefundResult(
        RefundGatewayResult result,
        String gatewayRefundReference
) {
}
