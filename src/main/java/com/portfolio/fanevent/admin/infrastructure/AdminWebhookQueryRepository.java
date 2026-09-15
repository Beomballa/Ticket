package com.portfolio.fanevent.admin.infrastructure;

import static com.portfolio.fanevent.payment.webhook.QPaymentWebhookInbox.paymentWebhookInbox;

import com.portfolio.fanevent.admin.application.AdminWebhookInboxSummary;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class AdminWebhookQueryRepository {

    private final JPAQueryFactory queryFactory;

    public AdminWebhookQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<AdminWebhookInboxSummary> search(Pageable pageable) {
        List<AdminWebhookInboxSummary> content = queryFactory
                .select(Projections.constructor(
                        AdminWebhookInboxSummary.class,
                        paymentWebhookInbox.id,
                        paymentWebhookInbox.providerEventId,
                        paymentWebhookInbox.eventType,
                        paymentWebhookInbox.result,
                        paymentWebhookInbox.status,
                        paymentWebhookInbox.attempts,
                        paymentWebhookInbox.occurredAt,
                        paymentWebhookInbox.receivedAt,
                        paymentWebhookInbox.processedAt,
                        paymentWebhookInbox.processingLeaseUntil,
                        paymentWebhookInbox.lastError))
                .from(paymentWebhookInbox)
                .orderBy(paymentWebhookInbox.receivedAt.desc(), paymentWebhookInbox.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(paymentWebhookInbox.count())
                .from(paymentWebhookInbox)
                .fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
