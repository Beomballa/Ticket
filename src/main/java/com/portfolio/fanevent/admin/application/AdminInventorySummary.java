package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.catalog.domain.InventoryType;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminInventorySummary(
        Long inventoryId,
        InventoryType type,
        String inventoryName,
        BigDecimal price,
        int totalQuantity,
        int availableQuantity,
        int reservedQuantity,
        Long eventSessionId,
        String eventSessionName,
        Instant eventStartsAt,
        Long eventId,
        String eventTitle
) {
}
