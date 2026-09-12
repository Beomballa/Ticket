package com.portfolio.fanevent.reservation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.reservation")
public record ReservationProperties(Duration holdTtl) {

    public ReservationProperties {
        if (holdTtl == null || holdTtl.isZero() || holdTtl.isNegative()) {
            throw new IllegalArgumentException("예약 선점 TTL은 양수여야 합니다.");
        }
    }
}
