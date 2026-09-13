package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.admin.infrastructure.AdminOutboxQueryRepository;
import com.portfolio.fanevent.outbox.application.OutboxProperties;
import com.portfolio.fanevent.outbox.domain.OutboxEvent;
import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import com.portfolio.fanevent.outbox.infrastructure.OutboxEventRepository;
import com.portfolio.fanevent.support.observability.OperationalMetrics;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOutboxService {

    private static final Logger log = LoggerFactory.getLogger(AdminOutboxService.class);

    private final AdminOutboxQueryRepository queryRepository;
    private final OutboxEventRepository eventRepository;
    private final OutboxProperties properties;
    private final Clock clock;
    private final OperationalMetrics metrics;
    private final JdbcTemplate jdbcTemplate;

    public AdminOutboxService(
            AdminOutboxQueryRepository queryRepository,
            OutboxEventRepository eventRepository,
            OutboxProperties properties,
            Clock clock,
            OperationalMetrics metrics,
            JdbcTemplate jdbcTemplate
    ) {
        this.queryRepository = queryRepository;
        this.eventRepository = eventRepository;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public Page<AdminOutboxEventSummary> search(
            AdminOutboxSearchCondition condition,
            Pageable pageable
    ) {
        validate(condition);
        return queryRepository.search(condition, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminOutboxEventSummary> searchExhaustedFailures(Pageable pageable) {
        return queryRepository.search(
                new AdminOutboxSearchCondition(
                        OutboxStatus.FAILED,
                        null,
                        null,
                        properties.maxAttempts(),
                        null,
                        null),
                pageable);
    }

    @Transactional
    public OutboxRetryResult retryExhaustedFailure(UUID eventId, String adminSubject) {
        OutboxEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Outbox 이벤트를 찾을 수 없습니다: " + eventId));
        int previousAttempts = event.getAttempts();
        if (event.getStatus() != OutboxStatus.FAILED
                || previousAttempts < properties.maxAttempts()) {
            metrics.outboxManualRetry("rejected");
            throw new OutboxManualRetryRejectedException(eventId);
        }

        int updated = eventRepository.resetExhaustedFailure(
                eventId, properties.maxAttempts(), clock.instant());
        if (updated != 1) {
            metrics.outboxManualRetry("rejected");
            throw new OutboxManualRetryRejectedException(eventId);
        }
        recordAudit(eventId, previousAttempts, adminSubject);
        metrics.outboxManualRetry("accepted");
        log.info(
                "Outbox manual retry accepted: eventId={}, previousAttempts={}, adminSubject={}",
                eventId, previousAttempts, adminSubject);
        return new OutboxRetryResult(eventId, OutboxStatus.PENDING, previousAttempts);
    }

    private void recordAudit(UUID eventId, int previousAttempts, String adminSubject) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    actor_member_id, action, target_type, target_id, details)
                VALUES (?, 'OUTBOX_MANUAL_RETRY', 'OUTBOX_EVENT', ?,
                        jsonb_build_object(
                            'previousAttempts', CAST(? AS integer),
                            'adminSubject', CAST(? AS text)))
                """,
                numericSubject(adminSubject),
                eventId.toString(),
                previousAttempts,
                adminSubject);
    }

    private Long numericSubject(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void validate(AdminOutboxSearchCondition condition) {
        if (condition.attemptsGoe() != null && condition.attemptsGoe() < 0) {
            throw new IllegalArgumentException("최소 시도 횟수는 0 이상이어야 합니다.");
        }
        if (condition.createdFrom() != null && condition.createdTo() != null
                && condition.createdFrom().isAfter(condition.createdTo())) {
            throw new IllegalArgumentException("생성 시작 시각은 종료 시각보다 늦을 수 없습니다.");
        }
    }
}
