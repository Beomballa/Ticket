package com.portfolio.fanevent.admin.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class ReservationCursorCodec {

    public String encode(ReservationCursor cursor) {
        String value = cursor.createdAt() + ":" + cursor.reservationId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public ReservationCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(':');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("예약 커서 형식이 올바르지 않습니다.");
            }
            return new ReservationCursor(
                    Instant.parse(decoded.substring(0, separator)),
                    Long.parseLong(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new IllegalArgumentException("예약 커서 형식이 올바르지 않습니다.");
        }
    }
}
