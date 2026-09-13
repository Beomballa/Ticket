package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MemberReservationDetail(
        Long reservationId,
        ReservationStatus status,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant expiredAt,
        Instant createdAt,
        List<Item> items
) {

    public MemberReservationDetail {
        items = List.copyOf(items);
    }

    public record Item(
            Long itemId,
            Long inventoryId,
            String inventoryName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineAmount,
            Long eventId,
            String eventTitle,
            Long eventSessionId,
            String eventSessionName,
            String venue,
            Instant eventStartsAt
    ) {
    }
}
