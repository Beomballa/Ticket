package com.portfolio.fanevent.catalog.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.cache.public-event")
public record PublicEventCacheProperties(boolean enabled, Duration ttl, String keyPrefix) {

    public PublicEventCacheProperties {
        ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "cache:event-detail:v1:" : keyPrefix;
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("공개 이벤트 캐시 TTL은 0보다 커야 합니다.");
        }
    }
}
