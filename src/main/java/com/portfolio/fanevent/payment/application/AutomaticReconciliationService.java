package com.portfolio.fanevent.payment.application;

import com.portfolio.fanevent.admin.application.AdminPaymentService;
import com.portfolio.fanevent.admin.application.AdminRefundService;
import com.portfolio.fanevent.payment.infrastructure.ReconciliationLeaseRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AutomaticReconciliationService {

    public static final String SYSTEM_ACTOR = "system:auto-reconciliation";

    private static final Logger log = LoggerFactory.getLogger(AutomaticReconciliationService.class);

    private final ReconciliationLeaseRepository leaseRepository;
    private final AdminPaymentService paymentService;
    private final AdminRefundService refundService;
    private final LateApprovalCompensationService compensationService;
    private final ReconciliationProperties properties;
    private final OperationalMetrics metrics;

    public AutomaticReconciliationService(
            ReconciliationLeaseRepository leaseRepository,
            AdminPaymentService paymentService,
            AdminRefundService refundService,
            LateApprovalCompensationService compensationService,
            ReconciliationProperties properties,
            OperationalMetrics metrics
    ) {
        this.leaseRepository = leaseRepository;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.compensationService = compensationService;
        this.properties = properties;
        this.metrics = metrics;
    }

    public int reconcileNextBatch() {
        int payments = process(
                "payment",
                () -> leaseRepository.claimPayments(
                        properties.batchSize(), properties.processingTimeout()),
                candidate -> paymentService.reconcile(
                        candidate.attemptId(), SYSTEM_ACTOR).resolved(),
                leaseRepository::completePayment);
        int compensations = process(
                "late-payment-compensation",
                () -> leaseRepository.claimCompensations(
                        properties.batchSize(), properties.processingTimeout()),
                candidate -> compensationService.compensate(candidate.attemptId()),
                leaseRepository::completeCompensation);
        int refunds = process(
                "refund",
                () -> leaseRepository.claimRefunds(
                        properties.batchSize(), properties.processingTimeout()),
                candidate -> refundService.reconcile(
                        candidate.attemptId(), SYSTEM_ACTOR).resolved(),
                leaseRepository::completeRefund);
        return payments + compensations + refunds;
    }

    private int process(
            String type,
            Supplier<List<ReconciliationCandidate>> claim,
            Function<ReconciliationCandidate, Boolean> reconcile,
            Completion completion
    ) {
        List<ReconciliationCandidate> candidates = claim.get();
        for (ReconciliationCandidate candidate : candidates) {
            try {
                boolean resolved = reconcile.apply(candidate);
                completion.complete(candidate, resolved ? Duration.ZERO : retryDelay(candidate.attempts()));
                metrics.automaticReconciliation(type, resolved ? "resolved" : "pending");
            } catch (RuntimeException exception) {
                completion.complete(candidate, retryDelay(candidate.attempts()));
                metrics.automaticReconciliation(type, "failed");
                log.warn("automatic reconciliation failed: type={}, attemptId={}, attempts={}",
                        type, candidate.attemptId(), candidate.attempts(), exception);
            }
        }
        return candidates.size();
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 19);
        Duration calculated = properties.baseRetryDelay().multipliedBy(multiplier);
        return calculated.compareTo(properties.maxRetryDelay()) > 0
                ? properties.maxRetryDelay()
                : calculated;
    }

    @FunctionalInterface
    private interface Completion {
        void complete(ReconciliationCandidate candidate, Duration retryDelay);
    }
}
