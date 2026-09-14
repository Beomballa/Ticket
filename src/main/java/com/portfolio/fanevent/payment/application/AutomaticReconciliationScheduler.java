package com.portfolio.fanevent.payment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.payment.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AutomaticReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomaticReconciliationScheduler.class);

    private final AutomaticReconciliationService service;

    public AutomaticReconciliationScheduler(AutomaticReconciliationService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${app.payment.reconciliation.initial-delay:PT30S}",
            fixedDelayString = "${app.payment.reconciliation.fixed-delay:PT10S}")
    public void reconcileUnknownAttempts() {
        int processed = service.reconcileNextBatch();
        if (processed > 0) {
            log.info("automatic reconciliation batch completed: processed={}", processed);
        }
    }
}
