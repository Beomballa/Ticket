package com.portfolio.fanevent.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refund_attempts")
public class RefundAttempt {

    @Id
    private UUID id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private Long reservationId;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Column(name = "gateway_idempotency_key", nullable = false, unique = true, length = 120)
    private String gatewayIdempotencyKey;

    @Column(name = "gateway_payment_reference", nullable = false, length = 120)
    private String gatewayPaymentReference;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundAttemptStatus status;

    @Column(name = "gateway_refund_reference", length = 120)
    private String gatewayRefundReference;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefundAttempt() {
    }

    private RefundAttempt(
            Long reservationId,
            UUID paymentAttemptId,
            String gatewayIdempotencyKey,
            String gatewayPaymentReference,
            BigDecimal amount,
            Instant requestedAt
    ) {
        this.id = UUID.randomUUID();
        this.reservationId = reservationId;
        this.paymentAttemptId = paymentAttemptId;
        this.gatewayIdempotencyKey = gatewayIdempotencyKey;
        this.gatewayPaymentReference = gatewayPaymentReference;
        this.amount = amount;
        this.status = RefundAttemptStatus.REQUESTED;
        this.requestedAt = requestedAt;
        this.createdAt = requestedAt;
        this.updatedAt = requestedAt;
    }

    public static RefundAttempt requested(
            Long reservationId,
            UUID paymentAttemptId,
            String gatewayIdempotencyKey,
            String gatewayPaymentReference,
            BigDecimal amount,
            Instant requestedAt
    ) {
        if (reservationId == null || paymentAttemptId == null
                || gatewayIdempotencyKey == null || gatewayIdempotencyKey.isBlank()
                || gatewayPaymentReference == null || gatewayPaymentReference.isBlank()
                || amount == null || amount.signum() < 0 || requestedAt == null) {
            throw new IllegalArgumentException("환불 시도 필수값이 누락되었습니다.");
        }
        return new RefundAttempt(
                reservationId,
                paymentAttemptId,
                gatewayIdempotencyKey,
                gatewayPaymentReference,
                amount,
                requestedAt);
    }

    public void succeed(String gatewayRefundReference, Instant resolvedAt) {
        requireUnresolved();
        this.status = RefundAttemptStatus.SUCCEEDED;
        this.gatewayRefundReference = gatewayRefundReference;
        this.lastError = null;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;
    }

    public void decline(String message, Instant resolvedAt) {
        requireUnresolved();
        this.status = RefundAttemptStatus.DECLINED;
        this.lastError = message;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;
    }

    public void markUnknown(String message, Instant occurredAt) {
        if (status == RefundAttemptStatus.SUCCEEDED || status == RefundAttemptStatus.DECLINED) {
            throw new IllegalStateException("완료된 환불 시도는 결과 불명으로 변경할 수 없습니다.");
        }
        this.status = RefundAttemptStatus.UNKNOWN;
        this.lastError = message;
        this.updatedAt = occurredAt;
    }

    private void requireUnresolved() {
        if (status == RefundAttemptStatus.SUCCEEDED || status == RefundAttemptStatus.DECLINED) {
            throw new IllegalStateException("이미 완료된 환불 시도입니다.");
        }
    }

    public UUID getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public String getGatewayIdempotencyKey() {
        return gatewayIdempotencyKey;
    }

    public String getGatewayPaymentReference() {
        return gatewayPaymentReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundAttemptStatus getStatus() {
        return status;
    }

    public String getGatewayRefundReference() {
        return gatewayRefundReference;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
