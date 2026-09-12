package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.catalog.domain.InventoryType;

public record AdminInventorySearchCondition(
        Long eventId,
        Long eventSessionId,
        InventoryType type,
        Integer availableQuantityLoe,
        Boolean soldOut
) {
}
