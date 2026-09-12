package com.portfolio.fanevent.catalog.domain;

public enum EventStatus {
    DRAFT,
    PUBLISHED,
    ON_SALE,
    CLOSED,
    CANCELLED;

    public boolean canTransitionTo(EventStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case DRAFT -> target == PUBLISHED || target == CANCELLED;
            case PUBLISHED -> target == ON_SALE || target == CANCELLED;
            case ON_SALE -> target == CLOSED || target == CANCELLED;
            case CLOSED, CANCELLED -> false;
        };
    }

    public boolean isCatalogEditable() {
        return this == DRAFT || this == PUBLISHED;
    }
}
