package com.portfolio.fanevent.catalog.application;

import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import java.time.Instant;

public record EventSearchCondition(
        String keyword,
        Long artistId,
        EventType type,
        EventStatus status,
        Instant salesFrom,
        Instant salesTo
) {
}
