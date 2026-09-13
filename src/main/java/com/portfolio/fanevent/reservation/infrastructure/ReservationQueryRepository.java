package com.portfolio.fanevent.reservation.infrastructure;

import static com.portfolio.fanevent.catalog.domain.QEvent.event;
import static com.portfolio.fanevent.catalog.domain.QEventSession.eventSession;
import static com.portfolio.fanevent.catalog.domain.QSellableInventory.sellableInventory;
import static com.portfolio.fanevent.reservation.domain.QReservation.reservation;
import static com.portfolio.fanevent.reservation.domain.QReservationItem.reservationItem;

import com.portfolio.fanevent.reservation.application.MemberReservationDetail;
import com.portfolio.fanevent.reservation.application.MemberReservationSearchCondition;
import com.portfolio.fanevent.reservation.application.MemberReservationSummary;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ReservationQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<MemberReservationSummary> search(
            Long memberId,
            MemberReservationSearchCondition condition,
            Pageable pageable
    ) {
        List<MemberReservationSummary> content = queryFactory
                .select(Projections.constructor(
                        MemberReservationSummary.class,
                        reservation.id,
                        reservation.status,
                        reservation.totalAmount,
                        reservation.expiresAt,
                        reservation.createdAt,
                        reservationItem.id.countDistinct(),
                        reservationItem.quantity.sum(),
                        event.id.countDistinct(),
                        Expressions.stringTemplate("min({0})", event.title)))
                .from(reservation)
                .join(reservation.items, reservationItem)
                .join(reservationItem.inventory, sellableInventory)
                .join(sellableInventory.eventSession, eventSession)
                .join(eventSession.event, event)
                .where(
                        reservation.member.id.eq(memberId),
                        statusEq(condition),
                        createdAtGoe(condition),
                        createdAtLoe(condition))
                .groupBy(
                        reservation.id,
                        reservation.status,
                        reservation.totalAmount,
                        reservation.expiresAt,
                        reservation.createdAt)
                .orderBy(reservation.createdAt.desc(), reservation.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(reservation.count())
                .from(reservation)
                .where(
                        reservation.member.id.eq(memberId),
                        statusEq(condition),
                        createdAtGoe(condition),
                        createdAtLoe(condition))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    public Optional<MemberReservationDetail> findOwnedDetail(Long memberId, Long reservationId) {
        Tuple header = queryFactory
                .select(
                        reservation.id,
                        reservation.status,
                        reservation.totalAmount,
                        reservation.expiresAt,
                        reservation.confirmedAt,
                        reservation.cancelledAt,
                        reservation.expiredAt,
                        reservation.createdAt)
                .from(reservation)
                .where(
                        reservation.id.eq(reservationId),
                        reservation.member.id.eq(memberId))
                .fetchOne();
        if (header == null) {
            return Optional.empty();
        }

        List<MemberReservationDetail.Item> items = queryFactory
                .select(
                        reservationItem.id,
                        sellableInventory.id,
                        sellableInventory.name,
                        reservationItem.quantity,
                        reservationItem.unitPrice,
                        event.id,
                        event.title,
                        eventSession.id,
                        eventSession.name,
                        eventSession.venue,
                        eventSession.startsAt)
                .from(reservationItem)
                .join(reservationItem.inventory, sellableInventory)
                .join(sellableInventory.eventSession, eventSession)
                .join(eventSession.event, event)
                .where(reservationItem.reservation.id.eq(reservationId))
                .orderBy(eventSession.startsAt.asc(), eventSession.id.asc(), reservationItem.id.asc())
                .fetch()
                .stream()
                .map(this::toItem)
                .toList();

        return Optional.of(new MemberReservationDetail(
                header.get(reservation.id),
                header.get(reservation.status),
                header.get(reservation.totalAmount),
                header.get(reservation.expiresAt),
                header.get(reservation.confirmedAt),
                header.get(reservation.cancelledAt),
                header.get(reservation.expiredAt),
                header.get(reservation.createdAt),
                items));
    }

    private MemberReservationDetail.Item toItem(Tuple row) {
        Integer quantity = row.get(reservationItem.quantity);
        BigDecimal unitPrice = row.get(reservationItem.unitPrice);
        int safeQuantity = quantity == null ? 0 : quantity;
        BigDecimal safeUnitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        return new MemberReservationDetail.Item(
                row.get(reservationItem.id),
                row.get(sellableInventory.id),
                row.get(sellableInventory.name),
                safeQuantity,
                safeUnitPrice,
                safeUnitPrice.multiply(BigDecimal.valueOf(safeQuantity)),
                row.get(event.id),
                row.get(event.title),
                row.get(eventSession.id),
                row.get(eventSession.name),
                row.get(eventSession.venue),
                row.get(eventSession.startsAt));
    }

    private BooleanExpression statusEq(MemberReservationSearchCondition condition) {
        return condition.status() == null ? null : reservation.status.eq(condition.status());
    }

    private BooleanExpression createdAtGoe(MemberReservationSearchCondition condition) {
        return condition.createdFrom() == null ? null : reservation.createdAt.goe(condition.createdFrom());
    }

    private BooleanExpression createdAtLoe(MemberReservationSearchCondition condition) {
        return condition.createdTo() == null ? null : reservation.createdAt.loe(condition.createdTo());
    }
}
