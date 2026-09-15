package com.portfolio.fanevent.payment.application;

import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import com.portfolio.fanevent.payment.domain.RefundPurpose;
import com.portfolio.fanevent.payment.infrastructure.RefundAttemptRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LateApprovalCompensationService {

    public static final String SYSTEM_ACTOR = "system:late-payment-compensation";

    private static final Logger log = LoggerFactory.getLogger(LateApprovalCompensationService.class);

    private final RefundAttemptRepository repository;
    private final PaymentGateway paymentGateway;
    private final RefundAttemptService attemptService;
    private final LateApprovalCompensationFinalizer finalizer;
    private final OperationalMetrics metrics;

    public LateApprovalCompensationService(
            RefundAttemptRepository repository,
            PaymentGateway paymentGateway,
            RefundAttemptService attemptService,
            LateApprovalCompensationFinalizer finalizer,
            OperationalMetrics metrics
    ) {
        this.repository = repository;
        this.paymentGateway = paymentGateway;
        this.attemptService = attemptService;
        this.finalizer = finalizer;
        this.metrics = metrics;
    }

    public boolean compensate(UUID attemptId) {
        RefundAttempt attempt = repository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "보상 환불 시도를 찾을 수 없습니다: " + attemptId));
        if (attempt.getPurpose() != RefundPurpose.LATE_PAYMENT_COMPENSATION) {
            throw new IllegalStateException("늦은 승인 보상 환불이 아닙니다.");
        }
        if (attempt.getStatus() != RefundAttemptStatus.REQUESTED) {
            return attempt.getStatus() != RefundAttemptStatus.UNKNOWN;
        }

        try {
            RefundResult result = paymentGateway.refund(
                    attempt.getReservationId(),
                    attempt.getAmount(),
                    attempt.getGatewayPaymentReference(),
                    attempt.getGatewayIdempotencyKey());
            finalizer.succeed(attemptId, result.gatewayRefundReference(), SYSTEM_ACTOR);
            metrics.lateApprovalCompensation("succeeded");
            log.info("late payment compensation succeeded: refundAttemptId={}, reservationId={}",
                    attemptId, attempt.getReservationId());
            return true;
        } catch (RefundDeclinedException exception) {
            finalizer.decline(attemptId, exception.getMessage(), SYSTEM_ACTOR);
            metrics.lateApprovalCompensation("declined");
            return true;
        } catch (RefundGatewayTimeoutException exception) {
            attemptService.markUnknown(attemptId, exception.getMessage());
            metrics.lateApprovalCompensation("unknown");
            return false;
        }
    }
}
