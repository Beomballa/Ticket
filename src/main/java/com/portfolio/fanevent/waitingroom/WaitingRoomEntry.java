package com.portfolio.fanevent.waitingroom;

import java.time.Instant;

public record WaitingRoomEntry(
        Long eventId,
        WaitingRoomStatus status,
        Long position,
        Long estimatedWaitSeconds,
        String admissionToken,
        Instant admissionExpiresAt
) {
    static WaitingRoomEntry disabled(Long eventId) {
        return new WaitingRoomEntry(eventId, WaitingRoomStatus.DISABLED, null, 0L, null, null);
    }
}
