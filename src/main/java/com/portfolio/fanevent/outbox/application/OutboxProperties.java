package com.portfolio.fanevent.outbox.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        boolean enabled,
        Duration initialDelay,
        Duration fixedDelay,
        int batchSize,
        Duration processingTimeout,
        int maxAttempts,
        Duration baseRetryDelay,
        Duration maxRetryDelay
) {

    public OutboxProperties {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("Outbox 초기 지연은 0 이상이어야 합니다.");
        }
        if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()
                || processingTimeout == null || processingTimeout.isZero()
                || processingTimeout.isNegative()
                || baseRetryDelay == null || baseRetryDelay.isZero()
                || baseRetryDelay.isNegative()
                || maxRetryDelay == null || maxRetryDelay.isZero()
                || maxRetryDelay.isNegative()) {
            throw new IllegalArgumentException("Outbox 시간 설정은 양수여야 합니다.");
        }
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("Outbox 배치 크기는 1 이상 1,000 이하여야 합니다.");
        }
        if (maxAttempts <= 0 || maxAttempts > 20) {
            throw new IllegalArgumentException("Outbox 최대 시도 횟수는 1 이상 20 이하여야 합니다.");
        }
        if (maxRetryDelay.compareTo(baseRetryDelay) < 0) {
            throw new IllegalArgumentException("Outbox 최대 재시도 지연은 기본 지연 이상이어야 합니다.");
        }
    }
}
