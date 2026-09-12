package com.portfolio.fanevent.reservation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.reservation.expiration")
public record ReservationExpirationProperties(
        boolean enabled,
        Duration initialDelay,
        Duration fixedDelay,
        int batchSize
) {

    public ReservationExpirationProperties {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("예약 만료 작업 초기 지연은 0 이상이어야 합니다.");
        }
        if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
            throw new IllegalArgumentException("예약 만료 작업 주기는 양수여야 합니다.");
        }
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("예약 만료 배치 크기는 1 이상 1,000 이하여야 합니다.");
        }
    }
}
