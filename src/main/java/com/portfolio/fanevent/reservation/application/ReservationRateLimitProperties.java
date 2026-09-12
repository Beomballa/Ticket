package com.portfolio.fanevent.reservation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.reservation.rate-limit")
public record ReservationRateLimitProperties(boolean enabled, int limit, Duration window, String keyPrefix) {

    public ReservationRateLimitProperties {
        limit = limit <= 0 ? 20 : limit;
        window = window == null ? Duration.ofMinutes(1) : window;
        keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "rate-limit:reservation:v1:" : keyPrefix;
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("예약 속도 제한 구간은 0보다 커야 합니다.");
        }
    }
}
