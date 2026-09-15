package com.portfolio.fanevent.waitingroom;

import com.portfolio.fanevent.support.security.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class AdmissionTokenService {

    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;
    private final Clock clock;

    public AdmissionTokenService(JwtProperties properties, Clock clock) {
        this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String issue(Long eventId, Long memberId, long generation, Instant expiresAt) {
        String payload = eventId + ":" + memberId + ":" + generation + ":" + expiresAt.getEpochSecond();
        return encode(payload.getBytes(StandardCharsets.UTF_8)) + "." + encode(sign(payload));
    }

    public AdmissionClaims verify(String token, Long expectedMemberId, Long expectedEventId) {
        if (token == null || token.isBlank()) {
            throw new AdmissionTokenException("ADMISSION_TOKEN_REQUIRED", "이 이벤트는 대기열 입장 토큰이 필요합니다.");
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw invalid();
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!java.security.MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) {
                throw invalid();
            }
            String[] values = payload.split(":", -1);
            if (values.length != 4) throw invalid();
            AdmissionClaims claims = new AdmissionClaims(
                    Long.valueOf(values[0]), Long.valueOf(values[1]),
                    Long.parseLong(values[2]), Instant.ofEpochSecond(Long.parseLong(values[3])));
            if (!claims.memberId().equals(expectedMemberId) || !claims.eventId().equals(expectedEventId)) {
                throw new AdmissionTokenException("ADMISSION_TOKEN_SCOPE_MISMATCH", "다른 회원이나 이벤트의 입장 토큰입니다.");
            }
            if (!claims.expiresAt().isAfter(clock.instant())) {
                throw new AdmissionTokenException("ADMISSION_TOKEN_EXPIRED", "입장 토큰이 만료되었습니다. 대기열 상태를 다시 확인해 주세요.");
            }
            return claims;
        } catch (AdmissionTokenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(("admission:v1:" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("입장 토큰 서명에 실패했습니다.", exception);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private AdmissionTokenException invalid() {
        return new AdmissionTokenException("ADMISSION_TOKEN_INVALID", "입장 토큰이 올바르지 않습니다.");
    }

    public record AdmissionClaims(Long eventId, Long memberId, long generation, Instant expiresAt) {
    }
}
