package com.portfolio.fanevent.admin.infrastructure;

import static com.portfolio.fanevent.payment.domain.QPaymentAttempt.paymentAttempt;

import com.portfolio.fanevent.admin.application.AdminPaymentAttemptSummary;
import com.portfolio.fanevent.payment.domain.PaymentAttemptStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class AdminPaymentQueryRepository {

    private final JPAQueryFactory queryFactory;

    public AdminPaymentQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<AdminPaymentAttemptSummary> searchUnknown(Pageable pageable) {
        List<AdminPaymentAttemptSummary> content = queryFactory
                .select(Projections.constructor(
                        AdminPaymentAttemptSummary.class,
                        paymentAttempt.id,
                        paymentAttempt.reservationId,
                        paymentAttempt.amount,
                        paymentAttempt.status,
                        paymentAttempt.gatewayReference,
                        paymentAttempt.lastError,
                        paymentAttempt.requestedAt,
                        paymentAttempt.resolvedAt))
                .from(paymentAttempt)
                .where(paymentAttempt.status.eq(PaymentAttemptStatus.UNKNOWN))
                .orderBy(paymentAttempt.requestedAt.asc(), paymentAttempt.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(paymentAttempt.count())
                .from(paymentAttempt)
                .where(paymentAttempt.status.eq(PaymentAttemptStatus.UNKNOWN))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
