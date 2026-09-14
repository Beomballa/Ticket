package com.portfolio.fanevent.support.observability;

import com.portfolio.fanevent.payment.application.ReconciliationBacklogSnapshot;
import com.portfolio.fanevent.payment.infrastructure.ReconciliationLeaseRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationBacklogMonitor {

    private final ReconciliationLeaseRepository repository;
    private final OperationalMetrics metrics;

    public ReconciliationBacklogMonitor(
            ReconciliationLeaseRepository repository,
            OperationalMetrics metrics
    ) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Scheduled(
            initialDelayString = "${app.payment.reconciliation.initial-delay:PT30S}",
            fixedDelayString = "${app.payment.reconciliation.metrics-interval:PT10S}")
    public void refresh() {
        ReconciliationBacklogSnapshot snapshot = repository.snapshot();
        metrics.updateReconciliationBacklog(
                snapshot.paymentCount(),
                snapshot.paymentOldestAgeSeconds(),
                snapshot.refundCount(),
                snapshot.refundOldestAgeSeconds());
    }
}
