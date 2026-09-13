package com.portfolio.fanevent.support.observability;

import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import com.portfolio.fanevent.outbox.infrastructure.OutboxEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxBacklogMonitor {

    private final OutboxEventRepository repository;
    private final OperationalMetrics metrics;

    public OutboxBacklogMonitor(OutboxEventRepository repository, OperationalMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT10S")
    @Transactional(readOnly = true)
    public void refresh() {
        metrics.updateOutboxBacklog(
                repository.countByStatus(OutboxStatus.PENDING),
                repository.countByStatus(OutboxStatus.FAILED),
                repository.countByStatus(OutboxStatus.PROCESSING));
    }
}
