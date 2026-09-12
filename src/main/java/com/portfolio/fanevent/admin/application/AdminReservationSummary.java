package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminReservationSummary(
        Long reservationId,
        Long memberId,
        String memberEmail,
        ReservationStatus status,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant createdAt,
        Long itemCount,
        Integer totalQuantity
) {
}
