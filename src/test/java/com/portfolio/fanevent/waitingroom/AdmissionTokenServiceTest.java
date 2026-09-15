package com.portfolio.fanevent.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.fanevent.support.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdmissionTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private final AdmissionTokenService service = new AdmissionTokenService(
            new JwtProperties("test-secret-is-long-enough-for-hs256-signing", "test", Duration.ofMinutes(30)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void tokenIsScopedToMemberAndEvent() {
        String token = service.issue(11L, 22L, 3L, NOW.plusSeconds(120));

        assertThat(service.verify(token, 22L, 11L).generation()).isEqualTo(3L);
        assertThatThrownBy(() -> service.verify(token, 22L, 12L))
                .isInstanceOf(AdmissionTokenException.class)
                .extracting("code").isEqualTo("ADMISSION_TOKEN_SCOPE_MISMATCH");
        assertThatThrownBy(() -> service.verify(token, 23L, 11L))
                .isInstanceOf(AdmissionTokenException.class)
                .extracting("code").isEqualTo("ADMISSION_TOKEN_SCOPE_MISMATCH");
    }

    @Test
    void expiredAndForgedTokensAreRejected() {
        String expired = service.issue(11L, 22L, 3L, NOW);
        String valid = service.issue(11L, 22L, 3L, NOW.plusSeconds(120));
        String forged = valid.substring(0, valid.length() - 1) + (valid.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> service.verify(expired, 22L, 11L))
                .isInstanceOf(AdmissionTokenException.class)
                .extracting("code").isEqualTo("ADMISSION_TOKEN_EXPIRED");
        assertThatThrownBy(() -> service.verify(forged, 22L, 11L))
                .isInstanceOf(AdmissionTokenException.class)
                .extracting("code").isEqualTo("ADMISSION_TOKEN_INVALID");
    }
}
