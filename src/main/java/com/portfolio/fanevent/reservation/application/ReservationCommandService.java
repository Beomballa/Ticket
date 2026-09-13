package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.catalog.domain.InsufficientStockException;
import com.portfolio.fanevent.catalog.domain.SellableInventory;
import com.portfolio.fanevent.catalog.infrastructure.SellableInventoryRepository;
import com.portfolio.fanevent.idempotency.application.IdempotencyService;
import com.portfolio.fanevent.idempotency.application.RequestFingerprint;
import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.member.infrastructure.MemberRepository;
import com.portfolio.fanevent.outbox.application.OutboxEventWriter;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.infrastructure.ReservationRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCommandService {

    private static final Logger log = LoggerFactory.getLogger(ReservationCommandService.class);

    private final MemberRepository memberRepository;
    private final SellableInventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationProperties properties;
    private final Clock clock;
    private final PaymentGateway paymentGateway;
    private final IdempotencyService idempotencyService;
    private final RequestFingerprint requestFingerprint;
    private final OutboxEventWriter outboxEventWriter;

    public ReservationCommandService(
            MemberRepository memberRepository,
            SellableInventoryRepository inventoryRepository,
            ReservationRepository reservationRepository,
            ReservationProperties properties,
            Clock clock,
            PaymentGateway paymentGateway,
            IdempotencyService idempotencyService,
            RequestFingerprint requestFingerprint,
            OutboxEventWriter outboxEventWriter
    ) {
        this.memberRepository = memberRepository;
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.properties = properties;
        this.clock = clock;
        this.paymentGateway = paymentGateway;
        this.idempotencyService = idempotencyService;
        this.requestFingerprint = requestFingerprint;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public ReservationResult hold(
            Long memberId,
            List<ReservationItemCommand> commands,
            String idempotencyKey
    ) {
        validateCommands(commands);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다: " + memberId));
        String fingerprint = requestFingerprint.sha256(canonicalHoldRequest(commands));
        return idempotencyService.execute(
                memberId,
                "reservation:create",
                idempotencyKey,
                fingerprint,
                201,
                ReservationResult.class,
                () -> createPendingReservation(member, commands));
    }

    private ReservationResult createPendingReservation(
            Member member,
            List<ReservationItemCommand> commands
    ) {
        Instant now = clock.instant();
        Reservation reservation = Reservation.pending(member, now.plus(properties.holdTtl()));

        commands.stream()
                .sorted(Comparator.comparing(ReservationItemCommand::inventoryId))
                .forEach(command -> reserveItem(reservation, command, now));

        Reservation saved = reservationRepository.saveAndFlush(reservation);
        log.info("reservation held: reservationId={}, memberId={}, itemCount={}, expiresAt={}",
                saved.getId(), member.getId(), commands.size(), saved.getExpiresAt());
        return ReservationResult.from(saved);
    }

    @Transactional
    public ReservationResult confirm(
            Long memberId,
            Long reservationId,
            String paymentToken,
            String idempotencyKey
    ) {
        String fingerprint = requestFingerprint.sha256(
                reservationId + "|paymentToken=" + paymentToken);
        return idempotencyService.execute(
                memberId,
                "reservation:confirm:" + reservationId,
                idempotencyKey,
                fingerprint,
                200,
                ReservationResult.class,
                () -> confirmReservation(memberId, reservationId, paymentToken));
    }

    private ReservationResult confirmReservation(
            Long memberId,
            Long reservationId,
            String paymentToken
    ) {
        Reservation reservation = findOwnedReservation(memberId, reservationId);
        Instant now = clock.instant();
        reservation.requireConfirmable(now);
        if (reservation.isConfirmed()) {
            return ReservationResult.from(reservation);
        }

        paymentGateway.authorize(reservation.getId(), reservation.getTotalAmount(), paymentToken);
        reservation.confirm(now);
        outboxEventWriter.appendReservationEvent(
                reservation, "RESERVATION_CONFIRMED", now);
        reservationRepository.flush();
        log.info("reservation confirmed: reservationId={}, memberId={}", reservationId, memberId);
        return ReservationResult.from(reservation);
    }

    @Transactional
    public ReservationResult cancel(Long memberId, Long reservationId) {
        Reservation reservation = findOwnedReservation(memberId, reservationId);
        reservation.requireCancellable();
        if (reservation.isCancelled()) {
            return ReservationResult.from(reservation);
        }

        if (reservation.isConfirmed()) {
            paymentGateway.refund(reservation.getId(), reservation.getTotalAmount());
        }
        Instant now = clock.instant();
        reservation.cancel(now);
        reservation.getItems().forEach(item -> item.releaseInventory());
        outboxEventWriter.appendReservationEvent(
                reservation, "RESERVATION_CANCELLED", now);
        reservationRepository.flush();
        log.info("reservation cancelled: reservationId={}, memberId={}", reservationId, memberId);
        return ReservationResult.from(reservation);
    }

    private Reservation findOwnedReservation(Long memberId, Long reservationId) {
        return reservationRepository.findOwnedWithItems(reservationId, memberId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "예약을 찾을 수 없습니다: " + reservationId));
    }

    private void reserveItem(
            Reservation reservation,
            ReservationItemCommand command,
            Instant now
    ) {
        int updated = inventoryRepository.decreaseAvailableQuantity(
                command.inventoryId(), command.quantity());
        SellableInventory inventory = inventoryRepository.findById(command.inventoryId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "재고를 찾을 수 없습니다: " + command.inventoryId()));
        inventory.requireReservable(command.quantity(), now);
        if (updated == 0) {
            throw new InsufficientStockException(
                    inventory.getId(), command.quantity(), inventory.getAvailableQuantity());
        }
        reservation.addItem(inventory, command.quantity());
    }

    private void validateCommands(List<ReservationItemCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("예약 항목은 한 개 이상이어야 합니다.");
        }
        Set<Long> inventoryIds = new HashSet<>();
        for (ReservationItemCommand command : commands) {
            if (command == null || command.inventoryId() == null || command.quantity() <= 0) {
                throw new IllegalArgumentException("예약 항목 값이 올바르지 않습니다.");
            }
            if (!inventoryIds.add(command.inventoryId())) {
                throw new IllegalArgumentException("같은 재고를 중복 요청할 수 없습니다.");
            }
        }
    }

    private String canonicalHoldRequest(List<ReservationItemCommand> commands) {
        return commands.stream()
                .sorted(Comparator.comparing(ReservationItemCommand::inventoryId))
                .map(command -> command.inventoryId() + ":" + command.quantity())
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
    }
}
