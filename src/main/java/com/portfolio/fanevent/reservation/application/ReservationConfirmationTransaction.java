package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.idempotency.application.IdempotencyService;
import com.portfolio.fanevent.outbox.application.OutboxEventWriter;
import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.infrastructure.ReservationRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationConfirmationTransaction {

    private static final Logger log = LoggerFactory.getLogger(ReservationConfirmationTransaction.class);

    private final ReservationRepository reservationRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxEventWriter outboxEventWriter;
    private final Clock clock;

    public ReservationConfirmationTransaction(
            ReservationRepository reservationRepository,
            IdempotencyService idempotencyService,
            OutboxEventWriter outboxEventWriter,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.idempotencyService = idempotencyService;
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConfirmationSnapshot prepare(Long memberId, Long reservationId) {
        Reservation reservation = findOwnedReservation(memberId, reservationId);
        Instant requestedAt = clock.instant();
        reservation.requireConfirmable(requestedAt);
        return new ConfirmationSnapshot(
                reservation.getId(),
                reservation.getTotalAmount(),
                requestedAt,
                reservation.isConfirmed());
    }

    @Transactional
    public ReservationResult complete(
            Long memberId,
            Long reservationId,
            String idempotencyKey,
            String fingerprint,
            Instant requestedAt
    ) {
        return idempotencyService.execute(
                memberId,
                "reservation:confirm:" + reservationId,
                idempotencyKey,
                fingerprint,
                200,
                ReservationResult.class,
                () -> confirmReservation(memberId, reservationId, requestedAt));
    }

    private ReservationResult confirmReservation(
            Long memberId,
            Long reservationId,
            Instant requestedAt
    ) {
        Reservation reservation = findOwnedReservation(memberId, reservationId);
        if (reservation.confirm(requestedAt)) {
            outboxEventWriter.appendReservationEvent(
                    reservation, "RESERVATION_CONFIRMED", clock.instant());
            reservationRepository.flush();
            log.info("reservation confirmed: reservationId={}, memberId={}", reservationId, memberId);
        }
        return ReservationResult.from(reservation);
    }

    private Reservation findOwnedReservation(Long memberId, Long reservationId) {
        return reservationRepository.findOwnedWithItems(reservationId, memberId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "예약을 찾을 수 없습니다: " + reservationId));
    }

    public record ConfirmationSnapshot(
            Long reservationId,
            BigDecimal amount,
            Instant requestedAt,
            boolean alreadyConfirmed
    ) {
    }
}
