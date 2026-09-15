package com.portfolio.fanevent.payment.webhook;

import java.time.Instant;

public record WebhookPayload(
        WebhookEventType eventType,
        String gatewayIdempotencyKey,
        String result,
        String gatewayReference,
        Instant occurredAt
) {
    public WebhookPayload {
        if (eventType == null || gatewayIdempotencyKey == null
                || gatewayIdempotencyKey.isBlank() || result == null
                || result.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException("PG 웹훅 필수값이 누락되었습니다.");
        }
    }
}
