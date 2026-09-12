package com.portfolio.fanevent.outbox.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.fanevent.outbox.domain.OutboxEvent;
import com.portfolio.fanevent.outbox.infrastructure.OutboxEventRepository;
import com.portfolio.fanevent.reservation.domain.Reservation;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendReservationEvent(Reservation reservation, String eventType, Instant occurredAt) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("reservationId", reservation.getId())
                .put("status", reservation.getStatus().name())
                .put("totalAmount", reservation.getTotalAmount())
                .put("occurredAt", occurredAt.toString());
        repository.save(OutboxEvent.pending(
                "RESERVATION",
                reservation.getId().toString(),
                eventType,
                payload,
                occurredAt));
    }
}
