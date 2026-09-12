package com.portfolio.fanevent.idempotency.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.idempotency")
public record IdempotencyProperties(Duration ttl) {

    public IdempotencyProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("멱등키 TTL은 양수여야 합니다.");
        }
    }
}
