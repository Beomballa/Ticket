package com.portfolio.fanevent.catalog.application;

import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import java.time.Instant;

public record EventSummary(
        Long id,
        String title,
        EventType type,
        EventStatus status,
        Long artistId,
        String artistName,
        Instant salesStartAt,
        Instant salesEndAt
) {
}
