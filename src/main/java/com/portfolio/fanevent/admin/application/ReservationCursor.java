package com.portfolio.fanevent.admin.application;

import java.time.Instant;

public record ReservationCursor(Instant createdAt, Long reservationId) {

    public ReservationCursor {
        if (createdAt == null || reservationId == null || reservationId <= 0) {
            throw new IllegalArgumentException("예약 커서 값이 올바르지 않습니다.");
        }
    }
}
