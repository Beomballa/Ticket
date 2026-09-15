package com.portfolio.fanevent.payment.webhook;

public record WebhookAcceptance(
        PaymentWebhookInbox event,
        boolean duplicate
) {
}
