package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.catalog.application.PublicEventCacheInvalidator;
import com.portfolio.fanevent.outbox.application.OutboxEventWriter;
import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.domain.ReservationItem;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReservationCancellationFinalizer {

    private final OutboxEventWriter outboxEventWriter;
    private final PublicEventCacheInvalidator cacheInvalidator;

    public ReservationCancellationFinalizer(
            OutboxEventWriter outboxEventWriter,
            PublicEventCacheInvalidator cacheInvalidator
    ) {
        this.outboxEventWriter = outboxEventWriter;
        this.cacheInvalidator = cacheInvalidator;
    }

    public boolean complete(Reservation reservation, Instant now) {
        if (!reservation.cancel(now)) {
            return false;
        }
        reservation.getItems().forEach(item -> item.releaseInventory());
        cacheInvalidator.evictAllAfterCommit(reservation.getItems().stream()
                .map(ReservationItem::getEventId)
                .collect(Collectors.toSet()));
        outboxEventWriter.appendReservationEvent(
                reservation, "RESERVATION_CANCELLED", now);
        return true;
    }
}
