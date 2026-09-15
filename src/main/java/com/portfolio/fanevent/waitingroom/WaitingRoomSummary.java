package com.portfolio.fanevent.waitingroom;

public record WaitingRoomSummary(
        Long eventId,
        String eventTitle,
        boolean enabled,
        long waitingCount,
        long admittedCount,
        int batchSize,
        int activeCapacity,
        long admissionTtlSeconds,
        long admittedLastMinute,
        String redisStatus
) {
}
