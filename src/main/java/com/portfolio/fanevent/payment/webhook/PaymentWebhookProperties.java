package com.portfolio.fanevent.payment.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.payment.webhook")
public record PaymentWebhookProperties(
        String secret,
        Duration timestampTolerance,
        Duration processingTimeout
) {
    public PaymentWebhookProperties {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("PG 웹훅 secret은 16자 이상이어야 합니다.");
        }
        if (timestampTolerance == null || timestampTolerance.isZero()
                || timestampTolerance.isNegative() || processingTimeout == null
                || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("PG 웹훅 시간 설정은 양수여야 합니다.");
        }
    }
}
