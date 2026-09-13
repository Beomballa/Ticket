package com.portfolio.fanevent.admin.infrastructure;

import static com.portfolio.fanevent.outbox.domain.QOutboxEvent.outboxEvent;

import com.portfolio.fanevent.admin.application.AdminOutboxEventSummary;
import com.portfolio.fanevent.admin.application.AdminOutboxSearchCondition;
import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class AdminOutboxQueryRepository {

    private final JPAQueryFactory queryFactory;

    public AdminOutboxQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<AdminOutboxEventSummary> search(
            AdminOutboxSearchCondition condition,
            Pageable pageable
    ) {
        List<AdminOutboxEventSummary> content = queryFactory
                .select(Projections.constructor(
                        AdminOutboxEventSummary.class,
                        outboxEvent.id,
                        outboxEvent.aggregateType,
                        outboxEvent.aggregateId,
                        outboxEvent.eventType,
                        outboxEvent.status,
                        outboxEvent.attempts,
                        outboxEvent.availableAt,
                        outboxEvent.publishedAt,
                        outboxEvent.lastError,
                        outboxEvent.createdAt))
                .from(outboxEvent)
                .where(predicates(condition))
                .orderBy(outboxEvent.createdAt.desc(), outboxEvent.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(outboxEvent.count())
                .from(outboxEvent)
                .where(predicates(condition))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private BooleanExpression[] predicates(AdminOutboxSearchCondition condition) {
        return new BooleanExpression[] {
                statusEq(condition.status()),
                eventTypeEq(condition.eventType()),
                aggregateIdEq(condition.aggregateId()),
                attemptsGoe(condition.attemptsGoe()),
                createdAtGoe(condition.createdFrom()),
                createdAtLoe(condition.createdTo())
        };
    }

    private BooleanExpression statusEq(OutboxStatus status) {
        return status == null ? null : outboxEvent.status.eq(status);
    }

    private BooleanExpression eventTypeEq(String eventType) {
        return hasText(eventType) ? outboxEvent.eventType.eq(eventType.trim()) : null;
    }

    private BooleanExpression aggregateIdEq(String aggregateId) {
        return hasText(aggregateId) ? outboxEvent.aggregateId.eq(aggregateId.trim()) : null;
    }

    private BooleanExpression attemptsGoe(Integer attempts) {
        return attempts == null ? null : outboxEvent.attempts.goe(attempts);
    }

    private BooleanExpression createdAtGoe(Instant createdFrom) {
        return createdFrom == null ? null : outboxEvent.createdAt.goe(createdFrom);
    }

    private BooleanExpression createdAtLoe(Instant createdTo) {
        return createdTo == null ? null : outboxEvent.createdAt.loe(createdTo);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
