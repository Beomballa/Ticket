package com.portfolio.fanevent.outbox.application;

import com.portfolio.fanevent.outbox.domain.OutboxEvent;
import com.portfolio.fanevent.outbox.infrastructure.OutboxEventRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxProcessingService {

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxProcessingService(
            OutboxEventRepository repository,
            OutboxProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxMessage> claimNextBatch() {
        Instant now = clock.instant();
        List<UUID> ids = repository.findAvailableIdsForUpdate(
                now, properties.maxAttempts(), properties.batchSize());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<OutboxEvent> events = repository.findAllById(ids);
        Instant leaseUntil = now.plus(properties.processingTimeout());
        events.forEach(event -> event.markProcessing(leaseUntil));
        repository.flush();
        return events.stream().map(OutboxMessage::from).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        OutboxEvent event = find(eventId);
        event.markPublished(clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, RuntimeException failure) {
        OutboxEvent event = find(eventId);
        event.markFailed(failure.getMessage(), clock.instant().plus(retryDelay(event.getAttempts())));
    }

    private OutboxEvent find(UUID eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("Outbox 이벤트를 찾을 수 없습니다: " + eventId));
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 19);
        Duration calculated = properties.baseRetryDelay().multipliedBy(multiplier);
        return calculated.compareTo(properties.maxRetryDelay()) > 0
                ? properties.maxRetryDelay()
                : calculated;
    }
}
