package com.portfolio.fanevent.admin.infrastructure;

import static com.portfolio.fanevent.payment.domain.QRefundAttempt.refundAttempt;

import com.portfolio.fanevent.admin.application.AdminCompensationSummary;
import com.portfolio.fanevent.payment.domain.RefundPurpose;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class AdminCompensationQueryRepository {

    private final JPAQueryFactory queryFactory;

    public AdminCompensationQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<AdminCompensationSummary> search(Pageable pageable) {
        List<AdminCompensationSummary> content = queryFactory
                .select(Projections.constructor(
                        AdminCompensationSummary.class,
                        refundAttempt.id,
                        refundAttempt.reservationId,
                        refundAttempt.paymentAttemptId,
                        refundAttempt.amount,
                        refundAttempt.status,
                        refundAttempt.reconciliationAttempts,
                        refundAttempt.requestedAt,
                        refundAttempt.resolvedAt,
                        refundAttempt.nextReconciliationAt,
                        refundAttempt.reconciliationLeaseUntil,
                        refundAttempt.lastError))
                .from(refundAttempt)
                .where(refundAttempt.purpose.eq(RefundPurpose.LATE_PAYMENT_COMPENSATION))
                .orderBy(refundAttempt.requestedAt.desc(), refundAttempt.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(refundAttempt.count())
                .from(refundAttempt)
                .where(refundAttempt.purpose.eq(RefundPurpose.LATE_PAYMENT_COMPENSATION))
                .fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
