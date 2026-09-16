package com.portfolio.fanevent.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.payment.refund")
public record RefundProcessingProperties(Duration processingTimeout) {

    public RefundProcessingProperties {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("환불 처리 유예시간은 양수여야 합니다.");
        }
    }
}
