package com.portfolio.fanevent.payment.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PaymentWebhookVerifier {

    private final PaymentWebhookProperties properties;
    private final Clock clock;

    public PaymentWebhookVerifier(PaymentWebhookProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void verify(String timestampHeader, String signatureHeader, String rawBody) {
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestampHeader);
        } catch (RuntimeException exception) {
            throw new WebhookRejectedException(
                    "WEBHOOK_TIMESTAMP_INVALID", "PG 웹훅 timestamp가 올바르지 않습니다.");
        }

        Instant signedAt = Instant.ofEpochSecond(epochSeconds);
        Duration age = Duration.between(signedAt, clock.instant()).abs();
        if (age.compareTo(properties.timestampTolerance()) > 0) {
            throw new WebhookRejectedException(
                    "WEBHOOK_TIMESTAMP_EXPIRED", "PG 웹훅 timestamp 허용 시간을 초과했습니다.");
        }

        byte[] supplied;
        try {
            String normalized = signatureHeader.startsWith("v1=")
                    ? signatureHeader.substring(3)
                    : signatureHeader;
            supplied = HexFormat.of().parseHex(normalized);
        } catch (RuntimeException exception) {
            throw invalidSignature();
        }
        byte[] expected = hmac(timestampHeader + "." + rawBody);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw invalidSignature();
        }
    }

    private byte[] hmac(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("PG 웹훅 서명 검증기를 초기화할 수 없습니다.", exception);
        }
    }

    private WebhookRejectedException invalidSignature() {
        return new WebhookRejectedException(
                "WEBHOOK_SIGNATURE_INVALID", "PG 웹훅 서명이 올바르지 않습니다.");
    }
}
