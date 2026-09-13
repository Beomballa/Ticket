package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.catalog.infrastructure.SellableInventoryRepository;
import com.portfolio.fanevent.outbox.application.OutboxEventWriter;
import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.domain.ReservationItem;
import com.portfolio.fanevent.reservation.infrastructure.ReservationRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationExpirationService {

    private final ReservationRepository reservationRepository;
    private final SellableInventoryRepository inventoryRepository;
    private final Clock clock;
    private final OutboxEventWriter outboxEventWriter;
    private final OperationalMetrics metrics;

    public ReservationExpirationService(
            ReservationRepository reservationRepository,
            SellableInventoryRepository inventoryRepository,
            Clock clock,
            OutboxEventWriter outboxEventWriter,
            OperationalMetrics metrics
    ) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.clock = clock;
        this.outboxEventWriter = outboxEventWriter;
        this.metrics = metrics;
    }

    @Transactional
    public int expireNextBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("예약 만료 배치 크기는 양수여야 합니다.");
        }
        Instant now = clock.instant();
        List<Long> reservationIds = reservationRepository.findExpiredIdsForUpdate(now, batchSize);
        if (reservationIds.isEmpty()) {
            return 0;
        }

        List<Reservation> reservations = reservationRepository.findAllWithItemsByIdIn(reservationIds);
        reservations.forEach(reservation -> expire(reservation, now));
        reservationRepository.flush();
        metrics.expired(reservations.size());
        return reservations.size();
    }

    private void expire(Reservation reservation, Instant now) {
        if (!reservation.expire(now)) {
            return;
        }
        reservation.getItems().stream()
                .sorted(Comparator.comparing(ReservationItem::getInventoryId))
                .forEach(this::releaseInventory);
        outboxEventWriter.appendReservationEvent(
                reservation, "RESERVATION_EXPIRED", now);
    }

    private void releaseInventory(ReservationItem item) {
        int updated = inventoryRepository.increaseAvailableQuantity(
                item.getInventoryId(), item.getQuantity());
        if (updated != 1) {
            throw new IllegalStateException(
                    "만료 예약의 재고를 반환할 수 없습니다: " + item.getInventoryId());
        }
    }
}
