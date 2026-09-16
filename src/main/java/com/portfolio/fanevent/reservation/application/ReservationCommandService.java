package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.catalog.application.PublicEventCacheInvalidator;
import com.portfolio.fanevent.catalog.domain.InsufficientStockException;
import com.portfolio.fanevent.catalog.domain.SellableInventory;
import com.portfolio.fanevent.catalog.infrastructure.SellableInventoryRepository;
import com.portfolio.fanevent.idempotency.application.IdempotencyService;
import com.portfolio.fanevent.idempotency.application.RequestFingerprint;
import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.member.infrastructure.MemberRepository;
import com.portfolio.fanevent.outbox.application.OutboxEventWriter;
import com.portfolio.fanevent.reservation.domain.Reservation;
import com.portfolio.fanevent.reservation.infrastructure.ReservationRepository;
import com.portfolio.fanevent.waitingroom.AdmissionTokenService;
import com.portfolio.fanevent.waitingroom.WaitingRoomService;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReservationCommandService {

    private static final Logger log = LoggerFactory.getLogger(ReservationCommandService.class);

    private final MemberRepository memberRepository;
    private final SellableInventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationProperties properties;
    private final Clock clock;
    private final ReservationConfirmationService confirmationService;
    private final ReservationCancellationService cancellationService;
    private final IdempotencyService idempotencyService;
    private final RequestFingerprint requestFingerprint;
    private final OutboxEventWriter outboxEventWriter;
    private final PublicEventCacheInvalidator cacheInvalidator;
    private final WaitingRoomService waitingRoomService;

    public ReservationCommandService(
            MemberRepository memberRepository,
            SellableInventoryRepository inventoryRepository,
            ReservationRepository reservationRepository,
            ReservationProperties properties,
            Clock clock,
            ReservationConfirmationService confirmationService,
            ReservationCancellationService cancellationService,
            IdempotencyService idempotencyService,
            RequestFingerprint requestFingerprint,
            OutboxEventWriter outboxEventWriter,
            PublicEventCacheInvalidator cacheInvalidator,
            WaitingRoomService waitingRoomService
    ) {
        this.memberRepository = memberRepository;
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.properties = properties;
        this.clock = clock;
        this.confirmationService = confirmationService;
        this.cancellationService = cancellationService;
        this.idempotencyService = idempotencyService;
        this.requestFingerprint = requestFingerprint;
        this.outboxEventWriter = outboxEventWriter;
        this.cacheInvalidator = cacheInvalidator;
        this.waitingRoomService = waitingRoomService;
    }

    @Transactional
    public ReservationResult hold(
            Long memberId,
            List<ReservationItemCommand> commands,
            String idempotencyKey,
            String admissionToken
    ) {
        validateCommands(commands);
        List<Long> eventIds = resolveEvents(commands);
        List<Long> protectedEventIds = eventIds.stream().filter(waitingRoomService::isOpen).toList();
        if (protectedEventIds.size() > 1 || (!protectedEventIds.isEmpty() && eventIds.size() > 1)) {
            throw new IllegalArgumentException("대기열 적용 이벤트는 다른 이벤트와 한 예약에 담을 수 없습니다.");
        }
        AdmissionTokenService.AdmissionClaims admission = protectedEventIds.isEmpty()
                ? null
                : waitingRoomService.claim(
                        admissionToken, memberId, protectedEventIds.getFirst(), idempotencyKey);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다: " + memberId));
        String fingerprint = requestFingerprint.sha256(canonicalHoldRequest(commands));
        ReservationResult result = idempotencyService.execute(
                memberId,
                "reservation:create",
                idempotencyKey,
                fingerprint,
                201,
                ReservationResult.class,
                () -> createPendingReservation(member, commands));
        completeAdmissionAfterCommit(admission, idempotencyKey);
        return result;
    }

    public ReservationResult hold(Long memberId, List<ReservationItemCommand> commands, String idempotencyKey) {
        return hold(memberId, commands, idempotencyKey, null);
    }

    private ReservationResult createPendingReservation(
            Member member,
            List<ReservationItemCommand> commands
    ) {
        Instant now = clock.instant();
        Reservation reservation = Reservation.pending(member, now.plus(properties.holdTtl()));
        Set<Long> affectedEventIds = new HashSet<>();

        commands.stream()
                .sorted(Comparator.comparing(ReservationItemCommand::inventoryId))
                .forEach(command -> affectedEventIds.add(reserveItem(reservation, command, now)));

        Reservation saved = reservationRepository.saveAndFlush(reservation);
        cacheInvalidator.evictAllAfterCommit(affectedEventIds);
        log.info("reservation held: reservationId={}, memberId={}, itemCount={}, expiresAt={}",
                saved.getId(), member.getId(), commands.size(), saved.getExpiresAt());
        return ReservationResult.from(saved);
    }

    public ReservationResult confirm(
            Long memberId,
            Long reservationId,
            String paymentToken,
            String idempotencyKey
    ) {
        return confirmationService.confirm(
                memberId, reservationId, paymentToken, idempotencyKey);
    }

    public ReservationResult cancel(Long memberId, Long reservationId) {
        return cancellationService.cancel(memberId, reservationId);
    }

    private Long reserveItem(
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
        return inventory.getEventId();
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

    private List<Long> resolveEvents(List<ReservationItemCommand> commands) {
        List<Long> eventIds = inventoryRepository.findDistinctEventIds(
                commands.stream().map(ReservationItemCommand::inventoryId).toList());
        if (eventIds.isEmpty()) {
            throw new EntityNotFoundException("예약할 재고를 찾을 수 없습니다.");
        }
        return eventIds;
    }

    private void completeAdmissionAfterCommit(
            AdmissionTokenService.AdmissionClaims admission,
            String idempotencyKey
    ) {
        if (admission == null) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                waitingRoomService.complete(admission, idempotencyKey);
            }
        });
    }
}
