package com.portfolio.fanevent.waitingroom;

public record WaitingRoomSummary(
        Long eventId,
        boolean enabled,
        long waitingCount,
        long admittedCount,
        int batchSize,
        int activeCapacity,
        long admittedLastMinute
) {
}
