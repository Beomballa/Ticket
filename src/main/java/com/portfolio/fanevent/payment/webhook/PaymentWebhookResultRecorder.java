package com.portfolio.fanevent.payment.webhook;

import com.portfolio.fanevent.payment.application.PaymentGatewayResult;
import com.portfolio.fanevent.payment.application.RefundGatewayResult;

public interface PaymentWebhookResultRecorder {

    void recordPayment(String gatewayKey, PaymentGatewayResult result, String reference);

    void recordRefund(String gatewayKey, RefundGatewayResult result, String reference);
}
