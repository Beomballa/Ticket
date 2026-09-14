package com.portfolio.fanevent.payment.application;

import com.portfolio.fanevent.payment.domain.PaymentAttempt;
import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import com.portfolio.fanevent.payment.infrastructure.PaymentAttemptRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentAttemptService {

    private final PaymentAttemptRepository repository;
    private final Clock clock;

    public PaymentAttemptService(PaymentAttemptRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt begin(
            Long reservationId,
            String gatewayIdempotencyKey,
            String paymentTokenFingerprint,
            BigDecimal amount
    ) {
        PaymentAttempt exactAttempt = repository
                .findByGatewayIdempotencyKey(gatewayIdempotencyKey)
                .orElse(null);
        if (exactAttempt != null) {
            PaymentAttempt validated = validateExisting(
                    exactAttempt, reservationId, paymentTokenFingerprint, amount);
            return recoverIncompleteRequest(validated);
        }

        PaymentAttempt unresolved = repository
                .findFirstByReservationIdAndStatusInOrderByRequestedAtDesc(
                        reservationId,
                        List.of(PaymentAttemptStatus.REQUESTED, PaymentAttemptStatus.UNKNOWN))
                .orElse(null);
        if (unresolved != null) {
            return recoverIncompleteRequest(unresolved);
        }

        return repository.saveAndFlush(PaymentAttempt.requested(
                reservationId,
                gatewayIdempotencyKey,
                paymentTokenFingerprint,
                amount,
                clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void approve(UUID attemptId, String gatewayReference) {
        PaymentAttempt attempt = find(attemptId);
        if (attempt.getStatus() == PaymentAttemptStatus.APPROVED) {
            return;
        }
        attempt.approve(gatewayReference, clock.instant());
        repository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decline(UUID attemptId, String message) {
        PaymentAttempt attempt = find(attemptId);
        if (attempt.getStatus() == PaymentAttemptStatus.DECLINED) {
            return;
        }
        attempt.decline(message, clock.instant());
        repository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(UUID attemptId, String message) {
        PaymentAttempt attempt = find(attemptId);
        attempt.markUnknown(message, clock.instant());
        repository.flush();
    }

    private PaymentAttempt validateExisting(
            PaymentAttempt attempt,
            Long reservationId,
            String paymentTokenFingerprint,
            BigDecimal amount
    ) {
        if (!attempt.getReservationId().equals(reservationId)
                || !attempt.getPaymentTokenFingerprint().equals(paymentTokenFingerprint)
                || attempt.getAmount().compareTo(amount) != 0) {
            throw new IllegalStateException("결제 시도 멱등키의 요청 정보가 일치하지 않습니다.");
        }
        return attempt;
    }

    private PaymentAttempt recoverIncompleteRequest(PaymentAttempt attempt) {
        if (attempt.getStatus() == PaymentAttemptStatus.REQUESTED) {
            attempt.markUnknown("이전 결제 승인 처리의 완료 여부를 확인해야 합니다.", clock.instant());
            repository.flush();
        }
        return attempt;
    }

    private PaymentAttempt find(UUID attemptId) {
        return repository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "결제 시도를 찾을 수 없습니다: " + attemptId));
    }
}
