package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.domain.RefundAttempt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO refund_attempts (
                id, reservation_id, payment_attempt_id, gateway_idempotency_key,
                gateway_payment_reference, amount, purpose, status, requested_at,
                reconciliation_attempts, version, created_at, updated_at
            ) VALUES (
                :id, :reservationId, :paymentAttemptId, :gatewayIdempotencyKey,
                :gatewayPaymentReference, :amount, 'RESERVATION_CANCELLATION',
                'REQUESTED', :requestedAt, 0, 0, :requestedAt, :requestedAt
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertCancellationRequestedIfAbsent(
            @Param("id") UUID id,
            @Param("reservationId") Long reservationId,
            @Param("paymentAttemptId") UUID paymentAttemptId,
            @Param("gatewayIdempotencyKey") String gatewayIdempotencyKey,
            @Param("gatewayPaymentReference") String gatewayPaymentReference,
            @Param("amount") BigDecimal amount,
            @Param("requestedAt") Instant requestedAt
    );

    Optional<RefundAttempt> findByReservationId(Long reservationId);

    Optional<RefundAttempt> findByGatewayIdempotencyKey(String gatewayIdempotencyKey);
}
