package com.portfolio.fanevent.outbox.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 80)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(
            String aggregateType,
            String aggregateId,
            String eventType,
            JsonNode payload,
            Instant now
    ) {
        if (aggregateType == null || aggregateType.isBlank()
                || aggregateId == null || aggregateId.isBlank()
                || eventType == null || eventType.isBlank()
                || payload == null || now == null) {
            throw new IllegalArgumentException("Outbox 이벤트 필수값이 누락되었습니다.");
        }
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.availableAt = now;
        this.createdAt = now;
    }

    public static OutboxEvent pending(
            String aggregateType,
            String aggregateId,
            String eventType,
            JsonNode payload,
            Instant now
    ) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, payload, now);
    }

    public void markProcessing(Instant leaseUntil) {
        status = OutboxStatus.PROCESSING;
        attempts++;
        availableAt = leaseUntil;
    }

    public void markPublished(Instant now) {
        requireProcessing();
        status = OutboxStatus.PUBLISHED;
        publishedAt = now;
        lastError = null;
    }

    public void markFailed(String error, Instant retryAt) {
        requireProcessing();
        status = OutboxStatus.FAILED;
        availableAt = retryAt;
        lastError = truncate(error);
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public int getAttempts() {
        return attempts;
    }

    private void requireProcessing() {
        if (status != OutboxStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 Outbox 이벤트가 아닙니다: " + id);
        }
    }

    private String truncate(String error) {
        String message = error == null || error.isBlank() ? "알 수 없는 발행 오류" : error;
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }
}
