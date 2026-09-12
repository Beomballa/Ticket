package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.time.Instant;

public record AdminReservationSearchCondition(
        ReservationStatus status,
        Long memberId,
        Long eventId,
        Instant createdFrom,
        Instant createdTo
) {
}
