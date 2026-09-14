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
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    private UUID id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "gateway_idempotency_key", nullable = false, unique = true, length = 120)
    private String gatewayIdempotencyKey;

    @Column(name = "payment_token_fingerprint", nullable = false, length = 64)
    private String paymentTokenFingerprint;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentAttemptStatus status;

    @Column(name = "gateway_reference", length = 120)
    private String gatewayReference;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;

    @Column(name = "next_reconciliation_at")
    private Instant nextReconciliationAt;

    @Column(name = "reconciliation_lease_until")
    private Instant reconciliationLeaseUntil;

    @Column(name = "last_reconciliation_at")
    private Instant lastReconciliationAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentAttempt() {
    }

    private PaymentAttempt(
            Long reservationId,
            String gatewayIdempotencyKey,
            String paymentTokenFingerprint,
            BigDecimal amount,
            Instant requestedAt
    ) {
        this.id = UUID.randomUUID();
        this.reservationId = reservationId;
        this.gatewayIdempotencyKey = gatewayIdempotencyKey;
        this.paymentTokenFingerprint = paymentTokenFingerprint;
        this.amount = amount;
        this.status = PaymentAttemptStatus.REQUESTED;
        this.requestedAt = requestedAt;
        this.createdAt = requestedAt;
        this.updatedAt = requestedAt;
    }

    public static PaymentAttempt requested(
            Long reservationId,
            String gatewayIdempotencyKey,
            String paymentTokenFingerprint,
            BigDecimal amount,
            Instant requestedAt
    ) {
        if (reservationId == null || gatewayIdempotencyKey == null
                || gatewayIdempotencyKey.isBlank() || paymentTokenFingerprint == null
                || paymentTokenFingerprint.isBlank() || amount == null
                || amount.signum() < 0 || requestedAt == null) {
            throw new IllegalArgumentException("결제 시도 필수값이 누락되었습니다.");
        }
        return new PaymentAttempt(
                reservationId, gatewayIdempotencyKey, paymentTokenFingerprint, amount, requestedAt);
    }

    public void approve(String gatewayReference, Instant resolvedAt) {
        requireUnresolved();
        this.status = PaymentAttemptStatus.APPROVED;
        this.gatewayReference = gatewayReference;
        this.lastError = null;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;
        clearReconciliationSchedule();
    }

    public void decline(String message, Instant resolvedAt) {
        requireUnresolved();
        this.status = PaymentAttemptStatus.DECLINED;
        this.lastError = message;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;
        clearReconciliationSchedule();
    }

    public void markUnknown(String message, Instant occurredAt) {
        if (status == PaymentAttemptStatus.APPROVED || status == PaymentAttemptStatus.DECLINED) {
            throw new IllegalStateException("완료된 결제 시도는 결과 불명으로 변경할 수 없습니다.");
        }
        this.status = PaymentAttemptStatus.UNKNOWN;
        this.lastError = message;
        this.updatedAt = occurredAt;
        this.nextReconciliationAt = occurredAt;
        this.reconciliationLeaseUntil = null;
    }

    private void requireUnresolved() {
        if (status == PaymentAttemptStatus.APPROVED || status == PaymentAttemptStatus.DECLINED) {
            throw new IllegalStateException("이미 완료된 결제 시도입니다.");
        }
    }

    private void clearReconciliationSchedule() {
        this.nextReconciliationAt = null;
        this.reconciliationLeaseUntil = null;
    }

    public UUID getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public String getGatewayIdempotencyKey() {
        return gatewayIdempotencyKey;
    }

    public String getPaymentTokenFingerprint() {
        return paymentTokenFingerprint;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
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
