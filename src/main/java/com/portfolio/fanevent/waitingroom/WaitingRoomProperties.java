package com.portfolio.fanevent.waitingroom;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.waiting-room")
public record WaitingRoomProperties(
        boolean enabled,
        String keyPrefix,
        Duration admissionTtl,
        int batchSize,
        int activeCapacity,
        int estimatedServiceSeconds,
        Duration initialDelay,
        Duration fixedDelay
) {
    public WaitingRoomProperties {
        keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "waiting-room:v1:" : keyPrefix;
        admissionTtl = positive(admissionTtl, Duration.ofMinutes(2));
        batchSize = batchSize <= 0 ? 50 : batchSize;
        activeCapacity = activeCapacity <= 0 ? 200 : activeCapacity;
        estimatedServiceSeconds = estimatedServiceSeconds <= 0 ? 3 : estimatedServiceSeconds;
        initialDelay = positive(initialDelay, Duration.ofSeconds(10));
        fixedDelay = positive(fixedDelay, Duration.ofSeconds(1));
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
