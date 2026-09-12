package com.portfolio.fanevent.catalog.application;

import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import com.portfolio.fanevent.catalog.domain.InventoryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record EventDetail(
        Long id,
        String title,
        String description,
        EventType type,
        EventStatus status,
        Long artistId,
        String artistName,
        Instant salesStartAt,
        Instant salesEndAt,
        List<SessionDetail> sessions
) {

    public record SessionDetail(
            Long id,
            String name,
            String venue,
            Instant startsAt,
            Instant salesStartAt,
            Instant salesEndAt,
            List<InventoryDetail> inventory
    ) {
    }

    public record InventoryDetail(
            Long id,
            InventoryType type,
            String name,
            BigDecimal price,
            int totalQuantity,
            int availableQuantity
    ) {
    }
}
