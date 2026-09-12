package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReservationResult(
        Long id,
        ReservationStatus status,
        BigDecimal totalAmount,
        Instant expiresAt,
        List<Item> items
) {

    public static ReservationResult from(Reservation reservation) {
        return new ReservationResult(
                reservation.getId(),
                reservation.getStatus(),
                reservation.getTotalAmount(),
                reservation.getExpiresAt(),
                reservation.getItems().stream()
                        .map(item -> new Item(
                                item.getInventoryId(),
                                item.getInventoryName(),
                                item.getQuantity(),
                                item.getUnitPrice()))
                        .toList());
    }

    public record Item(
            Long inventoryId,
            String inventoryName,
            int quantity,
            BigDecimal unitPrice
    ) {
    }
}
