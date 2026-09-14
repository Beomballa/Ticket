package com.portfolio.fanevent.payment.infrastructure;

import com.portfolio.fanevent.payment.domain.RefundAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, UUID> {

    Optional<RefundAttempt> findByReservationId(Long reservationId);
}
