package com.portfolio.fanevent.payment.webhook;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentWebhookInboxStore {

    private final PaymentWebhookInboxRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final PaymentWebhookProperties properties;
    private final Clock clock;

    public PaymentWebhookInboxStore(
            PaymentWebhookInboxRepository repository,
            JdbcTemplate jdbcTemplate,
            PaymentWebhookProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookAcceptance accept(
            String providerEventId,
            String payloadHash,
            String rawPayload,
            WebhookPayload payload
    ) {
        Instant now = clock.instant();
        int inserted = jdbcTemplate.update("""
                INSERT INTO payment_webhook_inbox (
                    id, provider_event_id, payload_hash, payload, event_type,
                    gateway_idempotency_key, result, gateway_reference, occurred_at,
                    status, attempts, received_at, updated_at, version)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?,
                        'RECEIVED', 0, ?, ?, 0)
                ON CONFLICT (provider_event_id) DO NOTHING
                """,
                UUID.randomUUID(),
                providerEventId,
                payloadHash,
                rawPayload,
                payload.eventType().name(),
                payload.gatewayIdempotencyKey(),
                payload.result(),
                payload.gatewayReference(),
                Timestamp.from(payload.occurredAt()),
                Timestamp.from(now),
                Timestamp.from(now));
        PaymentWebhookInbox event = repository.findByProviderEventId(providerEventId)
                .orElseThrow();
        if (!event.getPayloadHash().equals(payloadHash)) {
            throw new WebhookEventConflictException();
        }
        return new WebhookAcceptance(event, inserted == 0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int claim(UUID eventId) {
        Instant now = clock.instant();
        List<Integer> claims = jdbcTemplate.query("""
                UPDATE payment_webhook_inbox
                SET status = 'PROCESSING',
                    attempts = attempts + 1,
                    processing_lease_until = ?,
                    last_error = NULL,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND (
                    status IN ('RECEIVED', 'FAILED')
                    OR (status = 'PROCESSING' AND processing_lease_until <= ?)
                  )
                RETURNING attempts
                """,
                (resultSet, rowNumber) -> resultSet.getInt("attempts"),
                Timestamp.from(now.plus(properties.processingTimeout())),
                Timestamp.from(now),
                eventId,
                Timestamp.from(now));
        return claims.isEmpty() ? 0 : claims.getFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(UUID eventId, int claimedAttempt) {
        Instant now = clock.instant();
        jdbcTemplate.update("""
                UPDATE payment_webhook_inbox
                SET status = 'PROCESSED',
                    processing_lease_until = NULL,
                    last_error = NULL,
                    processed_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, Timestamp.from(now), Timestamp.from(now), eventId, claimedAttempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, int claimedAttempt, String error) {
        jdbcTemplate.update("""
                UPDATE payment_webhook_inbox
                SET status = 'FAILED',
                    processing_lease_until = NULL,
                    last_error = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, safeError(error), Timestamp.from(clock.instant()), eventId, claimedAttempt);
    }

    @Transactional(readOnly = true)
    public PaymentWebhookInbox find(UUID eventId) {
        return repository.findById(eventId).orElseThrow();
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "웹훅 처리 중 오류가 발생했습니다.";
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
