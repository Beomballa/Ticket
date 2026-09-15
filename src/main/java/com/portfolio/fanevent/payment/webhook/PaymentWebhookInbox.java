package com.portfolio.fanevent.payment.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_webhook_inbox")
public class PaymentWebhookInbox {

    @Id
    private UUID id;

    @Column(name = "provider_event_id", nullable = false, unique = true, length = 120)
    private String providerEventId;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private WebhookEventType eventType;

    @Column(name = "gateway_idempotency_key", nullable = false, length = 120)
    private String gatewayIdempotencyKey;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "gateway_reference", length = 120)
    private String gatewayReference;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookInboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "processing_lease_until")
    private Instant processingLeaseUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PaymentWebhookInbox() {
    }

    public UUID getId() {
        return id;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public WebhookEventType getEventType() {
        return eventType;
    }

    public String getGatewayIdempotencyKey() {
        return gatewayIdempotencyKey;
    }

    public String getResult() {
        return result;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public WebhookInboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getProcessingLeaseUntil() {
        return processingLeaseUntil;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
