package com.portfolio.fanevent.outbox.application;

import com.portfolio.fanevent.support.observability.OperationalMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxProcessingService processingService;
    private final IdempotentOutboxDispatcher dispatcher;
    private final OperationalMetrics metrics;

    public OutboxPublisher(
            OutboxProcessingService processingService,
            IdempotentOutboxDispatcher dispatcher,
            OperationalMetrics metrics
    ) {
        this.processingService = processingService;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    public int publishNextBatch() {
        int published = 0;
        for (OutboxMessage message : processingService.claimNextBatch()) {
            try {
                dispatcher.dispatch(message);
                processingService.markPublished(message.id());
                metrics.outbox("published");
                published++;
            } catch (RuntimeException failure) {
                processingService.markFailed(message.id(), failure);
                metrics.outbox("failed");
                log.warn(
                        "Outbox event delivery failed: eventId={}, aggregateId={}, eventType={}, attempt={}",
                        message.id(), message.aggregateId(), message.eventType(), message.attempt(), failure);
            }
        }
        return published;
    }
}
