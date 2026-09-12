package com.portfolio.fanevent.outbox.application;

public interface OutboxEventHandler {

    String consumerName();

    boolean supports(String eventType);

    void handle(OutboxMessage message);
}
