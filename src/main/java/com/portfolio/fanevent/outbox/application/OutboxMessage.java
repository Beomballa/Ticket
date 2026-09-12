package com.portfolio.fanevent.outbox.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.fanevent.outbox.domain.OutboxEvent;
import java.util.UUID;

public record OutboxMessage(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        JsonNode payload,
        int attempt
) {

    static OutboxMessage from(OutboxEvent event) {
        return new OutboxMessage(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getPayload().deepCopy(),
                event.getAttempts());
    }
}
