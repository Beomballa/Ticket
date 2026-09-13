package com.portfolio.fanevent.outbox.infrastructure;

import com.portfolio.fanevent.outbox.domain.OutboxEvent;
import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByStatus(OutboxStatus status);

    @Query(value = """
            SELECT event.id
            FROM outbox_events event
            WHERE event.status IN ('PENDING', 'FAILED', 'PROCESSING')
              AND event.available_at <= :now
              AND event.attempts < :maxAttempts
            ORDER BY event.available_at, event.created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findAvailableIdsForUpdate(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize
    );
}
