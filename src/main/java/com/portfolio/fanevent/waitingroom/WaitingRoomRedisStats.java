package com.portfolio.fanevent.waitingroom;

public record WaitingRoomRedisStats(
        boolean markerPresent,
        long waitingCount,
        long admittedCount,
        long admittedLastMinute
) {
}
