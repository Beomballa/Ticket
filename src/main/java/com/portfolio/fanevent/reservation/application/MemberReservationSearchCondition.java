package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.time.Instant;

public record MemberReservationSearchCondition(
        ReservationStatus status,
        Instant createdFrom,
        Instant createdTo
) {
}
