package com.portfolio.fanevent.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.payment.authorization")
public record PaymentAuthorizationProperties(Duration processingTimeout) {

    public PaymentAuthorizationProperties {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("결제 승인 처리 유예시간은 양수여야 합니다.");
        }
    }
}
