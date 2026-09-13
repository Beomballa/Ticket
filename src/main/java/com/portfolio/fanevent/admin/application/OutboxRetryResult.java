package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import java.util.UUID;

public record OutboxRetryResult(
        UUID eventId,
        OutboxStatus status,
        int previousAttempts
) {
}
