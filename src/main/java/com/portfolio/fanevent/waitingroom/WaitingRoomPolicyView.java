package com.portfolio.fanevent.waitingroom;

public record WaitingRoomPolicyView(
        Long eventId,
        String eventTitle,
        boolean enabled,
        int batchSize,
        int activeCapacity,
        long admissionTtlSeconds
) {
}
