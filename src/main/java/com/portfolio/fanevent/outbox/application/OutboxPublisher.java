package com.portfolio.fanevent.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxProcessingService processingService;
    private final IdempotentOutboxDispatcher dispatcher;

    public OutboxPublisher(
            OutboxProcessingService processingService,
            IdempotentOutboxDispatcher dispatcher
    ) {
        this.processingService = processingService;
        this.dispatcher = dispatcher;
    }

    public int publishNextBatch() {
        int published = 0;
        for (OutboxMessage message : processingService.claimNextBatch()) {
            try {
                dispatcher.dispatch(message);
                processingService.markPublished(message.id());
                published++;
            } catch (RuntimeException failure) {
                processingService.markFailed(message.id(), failure);
                log.warn(
                        "Outbox event delivery failed: eventId={}, eventType={}, attempt={}",
                        message.id(), message.eventType(), message.attempt(), failure);
            }
        }
        return published;
    }
}
