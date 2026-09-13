package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminOutboxEventSummary(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        OutboxStatus status,
        int attempts,
        Instant availableAt,
        Instant publishedAt,
        String lastError,
        Instant createdAt
) {
}
