package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.admin.infrastructure.AdminCompensationQueryRepository;
import com.portfolio.fanevent.payment.application.LateApprovalCompensationFinalizer;
import com.portfolio.fanevent.payment.application.LateApprovalCompensationService;
import com.portfolio.fanevent.payment.application.ReconciliationCandidate;
import com.portfolio.fanevent.payment.application.ReconciliationProperties;
import com.portfolio.fanevent.payment.domain.RefundAttempt;
import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import com.portfolio.fanevent.payment.domain.RefundPurpose;
import com.portfolio.fanevent.payment.infrastructure.ReconciliationLeaseRepository;
import com.portfolio.fanevent.payment.infrastructure.RefundAttemptRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCompensationService {

    private final AdminCompensationQueryRepository queryRepository;
    private final RefundAttemptRepository repository;
    private final AdminRefundService refundService;
    private final LateApprovalCompensationFinalizer finalizer;
    private final LateApprovalCompensationService compensationService;
    private final ReconciliationLeaseRepository leaseRepository;
    private final ReconciliationProperties properties;

    public AdminCompensationService(
            AdminCompensationQueryRepository queryRepository,
            RefundAttemptRepository repository,
            AdminRefundService refundService,
            LateApprovalCompensationFinalizer finalizer,
            LateApprovalCompensationService compensationService,
            ReconciliationLeaseRepository leaseRepository,
            ReconciliationProperties properties
    ) {
        this.queryRepository = queryRepository;
        this.repository = repository;
        this.refundService = refundService;
        this.finalizer = finalizer;
        this.compensationService = compensationService;
        this.leaseRepository = leaseRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Page<AdminCompensationSummary> search(Pageable pageable) {
        return queryRepository.search(pageable);
    }

    public AdminCompensationSummary retry(UUID attemptId, String actor) {
        RefundAttempt attempt = findCompensation(attemptId);
        if (attempt.getStatus() == RefundAttemptStatus.UNKNOWN) {
            refundService.reconcile(attemptId, actor);
            return summary(attemptId);
        }
        if (attempt.getStatus() != RefundAttemptStatus.SUCCEEDED) {
            finalizer.retry(attemptId, actor);
            ReconciliationCandidate candidate = leaseRepository.claimCompensation(
                    attemptId, properties.processingTimeout());
            if (candidate != null) {
                boolean resolved = compensationService.compensate(attemptId);
                leaseRepository.completeCompensation(
                        candidate, resolved ? Duration.ZERO : properties.baseRetryDelay());
            }
        }
        return summary(attemptId);
    }

    private RefundAttempt findCompensation(UUID attemptId) {
        RefundAttempt attempt = repository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "보상 환불 시도를 찾을 수 없습니다: " + attemptId));
        if (attempt.getPurpose() != RefundPurpose.LATE_PAYMENT_COMPENSATION) {
            throw new IllegalStateException("늦은 승인 보상 환불이 아닙니다.");
        }
        return attempt;
    }

    private AdminCompensationSummary summary(UUID attemptId) {
        RefundAttempt attempt = findCompensation(attemptId);
        return new AdminCompensationSummary(
                attempt.getId(), attempt.getReservationId(), attempt.getPaymentAttemptId(),
                attempt.getAmount(), attempt.getStatus(), attempt.getReconciliationAttempts(),
                attempt.getRequestedAt(), attempt.getResolvedAt(), attempt.getNextReconciliationAt(),
                attempt.getReconciliationLeaseUntil(), attempt.getLastError());
    }
}
