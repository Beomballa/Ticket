package com.portfolio.fanevent.outbox.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.outbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxPublisherScheduler {

    private final OutboxPublisher publisher;

    public OutboxPublisherScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(
            initialDelayString = "${app.outbox.initial-delay:PT10S}",
            fixedDelayString = "${app.outbox.fixed-delay:PT1S}")
    public void publishEvents() {
        publisher.publishNextBatch();
    }
}
