package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.infrastructure.ReservationRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCancellationTransaction {

    private static final Logger log = LoggerFactory.getLogger(ReservationCancellationTransaction.class);

    private final ReservationRepository reservationRepository;
    private final ReservationCancellationFinalizer cancellationFinalizer;
    private final Clock clock;

    public ReservationCancellationTransaction(
            ReservationRepository reservationRepository,
            ReservationCancellationFinalizer cancellationFinalizer,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.cancellationFinalizer = cancellationFinalizer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CancellationSnapshot prepare(Long memberId, Long reservationId) {
        Reservation reservation = findOwnedReservation(memberId, reservationId);
        reservation.requireCancellable();
        return new CancellationSnapshot(
                reservation.getId(),
                reservation.getTotalAmount(),
                reservation.isConfirmed(),
                reservation.isCancelled());
    }

    @Transactional
    public ReservationResult complete(Long memberId, Long reservationId) {
        reservationRepository.lockOwnedById(reservationId, memberId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "예약을 찾을 수 없습니다: " + reservationId));
        Reservation reservation = findOwnedReservation(memberId, reservationId);
        if (cancellationFinalizer.complete(reservation, clock.instant())) {
            reservationRepository.flush();
            log.info("reservation cancelled: reservationId={}, memberId={}", reservationId, memberId);
        }
        return ReservationResult.from(reservation);
    }

    private Reservation findOwnedReservation(Long memberId, Long reservationId) {
        return reservationRepository.findOwnedWithItems(reservationId, memberId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "예약을 찾을 수 없습니다: " + reservationId));
    }

    public record CancellationSnapshot(
            Long reservationId,
            BigDecimal amount,
            boolean confirmed,
            boolean alreadyCancelled
    ) {
    }
}
