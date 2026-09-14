package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.domain.PaymentAttempt;
import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByGatewayIdempotencyKey(String gatewayIdempotencyKey);

    Optional<PaymentAttempt> findFirstByReservationIdAndStatusInOrderByRequestedAtDesc(
            Long reservationId,
            Collection<PaymentAttemptStatus> statuses
    );
}
