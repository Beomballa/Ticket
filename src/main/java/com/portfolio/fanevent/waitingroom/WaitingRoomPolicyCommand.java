package com.portfolio.fanevent.waitingroom;

import java.time.Duration;

public record WaitingRoomPolicyCommand(
        boolean enabled,
        int batchSize,
        int activeCapacity,
        Duration admissionTtl
) {
}
