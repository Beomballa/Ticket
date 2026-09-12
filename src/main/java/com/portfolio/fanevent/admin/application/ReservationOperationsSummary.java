package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.math.BigDecimal;
import java.util.Map;

public record ReservationOperationsSummary(
        long totalReservations,
        BigDecimal confirmedSalesAmount,
        Map<ReservationStatus, Long> statusCounts
) {
}
