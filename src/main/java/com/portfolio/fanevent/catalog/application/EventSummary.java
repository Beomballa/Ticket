package com.portfolio.fanevent.catalog.application;

import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import java.time.Instant;
import java.math.BigDecimal;

public record EventSummary(
        Long id,
        String title,
        EventType type,
        EventStatus status,
        Long artistId,
        String artistName,
        Instant salesStartAt,
        Instant salesEndAt,
        Overview overview
) {
    public EventSummary(Long id, String title, EventType type, EventStatus status,
                        Long artistId, String artistName, Instant salesStartAt, Instant salesEndAt) {
        this(id, title, type, status, artistId, artistName, salesStartAt, salesEndAt, null);
    }

    public EventSummary withOverview(Overview value) {
        return new EventSummary(id, title, type, status, artistId, artistName, salesStartAt, salesEndAt, value);
    }

    public record Overview(Instant startsAt, Instant endsAt, String venue, BigDecimal minPrice) {
    }
}
