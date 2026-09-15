package com.portfolio.fanevent.payment.webhook;

public record WebhookReceiveResult(
        String providerEventId,
        WebhookInboxStatus status,
        boolean duplicate
) {
}
