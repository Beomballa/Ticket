package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import java.time.Instant;

public record AdminOutboxSearchCondition(
        OutboxStatus status,
        String eventType,
        String aggregateId,
        Integer attemptsGoe,
        Instant createdFrom,
        Instant createdTo
) {
}
