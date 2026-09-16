package com.portfolio.fanevent.payment.application;

import com.portfolio.fanevent.idempotency.application.IdempotencyInProgressException;
import com.portfolio.fanevent.payment.domain.PaymentAttempt;
import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import com.portfolio.fanevent.payment.infrastructure.PaymentAttemptRepository;
import com.portfolio.fanevent.payment.infrastructure.RefundAttemptRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundAttemptService {

    private final RefundAttemptRepository refundRepository;
    private final PaymentAttemptRepository paymentRepository;
    private final RefundProcessingProperties properties;
    private final OperationalMetrics metrics;
    private final Clock clock;

    public RefundAttemptService(
            RefundAttemptRepository refundRepository,
            PaymentAttemptRepository paymentRepository,
            RefundProcessingProperties properties,
            OperationalMetrics metrics,
            Clock clock
    ) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundAttempt begin(Long reservationId, BigDecimal amount) {
        RefundAttempt existing = refundRepository.findByReservationId(reservationId).orElse(null);
        if (existing != null) {
            validateExisting(existing, amount);
            return recoverIncompleteRequest(existing);
        }

        PaymentAttempt payment = paymentRepository
                .findFirstByReservationIdAndStatusOrderByResolvedAtDesc(
                        reservationId, PaymentAttemptStatus.APPROVED)
                .orElseThrow(() -> new EntityNotFoundException(
                        "승인된 결제 시도를 찾을 수 없습니다: " + reservationId));
        String gatewayKey = "reservation-refund-" + reservationId;
        Instant requestedAt = clock.instant();
        int inserted = refundRepository.insertCancellationRequestedIfAbsent(
                UUID.randomUUID(),
                reservationId,
                payment.getId(),
                gatewayKey,
                payment.getGatewayReference(),
                amount,
                requestedAt);
        RefundAttempt attempt = refundRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new IllegalStateException(
                        "환불 시도 원자 생성 결과를 찾을 수 없습니다."));
        validateExisting(attempt, amount);
        if (inserted == 1) {
            metrics.refundExecutionClaim("created");
            return attempt;
        }
        return recoverIncompleteRequest(attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID attemptId, String gatewayRefundReference) {
        RefundAttempt attempt = find(attemptId);
        if (attempt.getStatus() == RefundAttemptStatus.SUCCEEDED) {
            return;
        }
        attempt.succeed(gatewayRefundReference, clock.instant());
        refundRepository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decline(UUID attemptId, String message) {
        RefundAttempt attempt = find(attemptId);
        if (attempt.getStatus() == RefundAttemptStatus.DECLINED) {
            return;
        }
        attempt.decline(message, clock.instant());
        refundRepository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(UUID attemptId, String message) {
        RefundAttempt attempt = find(attemptId);
        attempt.markUnknown(message, clock.instant());
        refundRepository.flush();
    }

    private void validateExisting(RefundAttempt attempt, BigDecimal amount) {
        if (attempt.getAmount().compareTo(amount) != 0) {
            throw new IllegalStateException("기존 환불 시도의 금액이 예약 금액과 일치하지 않습니다.");
        }
    }

    private RefundAttempt recoverIncompleteRequest(RefundAttempt attempt) {
        if (attempt.getStatus() == RefundAttemptStatus.REQUESTED) {
            Instant now = clock.instant();
            if (attempt.getRequestedAt().plus(properties.processingTimeout()).isAfter(now)) {
                metrics.refundExecutionClaim("in_progress");
                throw new IdempotencyInProgressException();
            }
            attempt.markUnknown("이전 환불 처리의 완료 여부를 확인해야 합니다.", clock.instant());
            refundRepository.flush();
            metrics.refundExecutionClaim("recovered_unknown");
        } else {
            metrics.refundExecutionClaim("reused");
        }
        return attempt;
    }

    private RefundAttempt find(UUID attemptId) {
        return refundRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "환불 시도를 찾을 수 없습니다: " + attemptId));
    }
}
