package com.portfolio.fanevent.outbox.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotentOutboxDispatcher {

    private final List<OutboxEventHandler> handlers;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public IdempotentOutboxDispatcher(
            List<OutboxEventHandler> handlers,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.handlers = handlers;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void dispatch(OutboxMessage message) {
        List<OutboxEventHandler> supportedHandlers = handlers.stream()
                .filter(handler -> handler.supports(message.eventType()))
                .toList();
        if (supportedHandlers.isEmpty()) {
            throw new IllegalStateException("Outbox 이벤트 처리기가 없습니다: " + message.eventType());
        }
        supportedHandlers.forEach(handler -> dispatch(handler, message));
    }

    private void dispatch(OutboxEventHandler handler, OutboxMessage message) {
        transactionTemplate.executeWithoutResult(status -> {
            int acquired = jdbcTemplate.update("""
                    INSERT INTO consumed_outbox_events (consumer_name, event_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, handler.consumerName(), message.id());
            if (acquired == 1) {
                handler.handle(message);
            }
        });
    }
}
