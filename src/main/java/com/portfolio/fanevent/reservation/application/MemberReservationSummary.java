package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record MemberReservationSummary(
        Long reservationId,
        ReservationStatus status,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant createdAt,
        Long itemCount,
        Integer totalQuantity,
        Long eventCount,
        String representativeEventTitle
) {
}
