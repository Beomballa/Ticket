package com.portfolio.fanevent.admin.infrastructure;

import static com.portfolio.fanevent.payment.domain.QRefundAttempt.refundAttempt;

import com.portfolio.fanevent.admin.application.AdminRefundAttemptSummary;
import com.portfolio.fanevent.payment.domain.RefundAttemptStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRefundQueryRepository {

    private final JPAQueryFactory queryFactory;

    public AdminRefundQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<AdminRefundAttemptSummary> searchUnknown(Pageable pageable) {
        List<AdminRefundAttemptSummary> content = queryFactory
                .select(Projections.constructor(
                        AdminRefundAttemptSummary.class,
                        refundAttempt.id,
                        refundAttempt.reservationId,
                        refundAttempt.paymentAttemptId,
                        refundAttempt.amount,
                        refundAttempt.status,
                        refundAttempt.gatewayRefundReference,
                        refundAttempt.lastError,
                        refundAttempt.requestedAt,
                        refundAttempt.resolvedAt,
                        refundAttempt.reconciliationAttempts,
                        refundAttempt.nextReconciliationAt,
                        refundAttempt.reconciliationLeaseUntil,
                        refundAttempt.lastReconciliationAt))
                .from(refundAttempt)
                .where(refundAttempt.status.eq(RefundAttemptStatus.UNKNOWN))
                .orderBy(refundAttempt.requestedAt.asc(), refundAttempt.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(refundAttempt.count())
                .from(refundAttempt)
                .where(refundAttempt.status.eq(RefundAttemptStatus.UNKNOWN))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
