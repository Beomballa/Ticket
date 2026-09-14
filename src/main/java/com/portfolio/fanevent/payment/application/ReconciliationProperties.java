package com.portfolio.fanevent.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.payment.reconciliation")
public record ReconciliationProperties(
        boolean enabled,
        Duration initialDelay,
        Duration fixedDelay,
        int batchSize,
        Duration processingTimeout,
        Duration baseRetryDelay,
        Duration maxRetryDelay,
        Duration metricsInterval
) {

    public ReconciliationProperties {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("자동 대사 초기 지연은 0 이상이어야 합니다.");
        }
        if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()
                || processingTimeout == null || processingTimeout.isZero()
                || processingTimeout.isNegative()
                || baseRetryDelay == null || baseRetryDelay.isZero()
                || baseRetryDelay.isNegative()
                || maxRetryDelay == null || maxRetryDelay.isZero()
                || maxRetryDelay.isNegative()
                || metricsInterval == null || metricsInterval.isZero()
                || metricsInterval.isNegative()) {
            throw new IllegalArgumentException("자동 대사 시간 설정은 양수여야 합니다.");
        }
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("자동 대사 배치 크기는 1 이상 1,000 이하여야 합니다.");
        }
        if (maxRetryDelay.compareTo(baseRetryDelay) < 0) {
            throw new IllegalArgumentException("자동 대사 최대 재시도 지연은 기본 지연 이상이어야 합니다.");
        }
    }
}
