package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.payment.webhook.WebhookEventType;
import com.portfolio.fanevent.payment.webhook.WebhookInboxStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminWebhookInboxSummary(
        UUID id,
        String providerEventId,
        WebhookEventType eventType,
        String result,
        WebhookInboxStatus status,
        int attempts,
        Instant occurredAt,
        Instant receivedAt,
        Instant processedAt,
        Instant processingLeaseUntil,
        String lastError
) {
}
