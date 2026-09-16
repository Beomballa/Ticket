package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.domain.PaymentAttempt;
import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO payment_attempts (
                id, reservation_id, gateway_idempotency_key, payment_token_fingerprint,
                amount, status, requested_at, reconciliation_attempts, version,
                created_at, updated_at
            ) VALUES (
                :id, :reservationId, :gatewayIdempotencyKey, :paymentTokenFingerprint,
                :amount, 'REQUESTED', :requestedAt, 0, 0, :requestedAt, :requestedAt
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertRequestedIfAbsent(
            @Param("id") UUID id,
            @Param("reservationId") Long reservationId,
            @Param("gatewayIdempotencyKey") String gatewayIdempotencyKey,
            @Param("paymentTokenFingerprint") String paymentTokenFingerprint,
            @Param("amount") BigDecimal amount,
            @Param("requestedAt") Instant requestedAt
    );

    Optional<PaymentAttempt> findByGatewayIdempotencyKey(String gatewayIdempotencyKey);

    Optional<PaymentAttempt> findFirstByReservationIdAndStatusInOrderByRequestedAtDesc(
            Long reservationId,
            Collection<PaymentAttemptStatus> statuses
    );

    Optional<PaymentAttempt> findFirstByReservationIdAndStatusOrderByResolvedAtDesc(
            Long reservationId,
            PaymentAttemptStatus status
    );
}
