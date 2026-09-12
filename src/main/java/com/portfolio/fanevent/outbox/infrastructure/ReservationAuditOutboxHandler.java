package com.portfolio.fanevent.outbox.infrastructure;

import com.portfolio.fanevent.outbox.application.OutboxEventHandler;
import com.portfolio.fanevent.outbox.application.OutboxMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReservationAuditOutboxHandler implements OutboxEventHandler {

    private static final String CONSUMER_NAME = "reservation-audit-log";

    private final JdbcTemplate jdbcTemplate;

    public ReservationAuditOutboxHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.startsWith("RESERVATION_");
    }

    @Override
    public void handle(OutboxMessage message) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (action, target_type, target_id, details)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """,
                message.eventType(),
                message.aggregateType(),
                message.aggregateId(),
                message.payload().toString());
    }
}
