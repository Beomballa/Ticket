package com.portfolio.fanevent.reservation.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    FAILED;

    public boolean canTransitionTo(ReservationStatus target) {
        if (target == this) {
            return true;
        }
        return allowedTargets().contains(target);
    }

    private Set<ReservationStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, EXPIRED, CANCELLED, FAILED);
            case CONFIRMED -> EnumSet.of(CANCELLED);
            case EXPIRED, CANCELLED, FAILED -> EnumSet.noneOf(ReservationStatus.class);
        };
    }
}
