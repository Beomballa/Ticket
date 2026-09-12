package com.portfolio.fanevent.admin.infrastructure;

import static com.portfolio.fanevent.catalog.domain.QEvent.event;
import static com.portfolio.fanevent.catalog.domain.QEventSession.eventSession;
import static com.portfolio.fanevent.catalog.domain.QSellableInventory.sellableInventory;
import static com.portfolio.fanevent.member.domain.QMember.member;
import static com.portfolio.fanevent.reservation.domain.QReservation.reservation;
import static com.portfolio.fanevent.reservation.domain.QReservationItem.reservationItem;

import com.portfolio.fanevent.admin.application.AdminInventorySearchCondition;
import com.portfolio.fanevent.admin.application.AdminInventorySummary;
import com.portfolio.fanevent.admin.application.AdminReservationSearchCondition;
import com.portfolio.fanevent.admin.application.AdminReservationSummary;
import com.portfolio.fanevent.admin.application.ReservationCursor;
import com.portfolio.fanevent.admin.application.ReservationOperationsSummary;
import com.portfolio.fanevent.catalog.domain.InventoryType;
import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class AdminQueryRepository {

    private final JPAQueryFactory queryFactory;

    public AdminQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<AdminReservationSummary> searchReservations(
            AdminReservationSearchCondition condition,
            Pageable pageable
    ) {
        List<AdminReservationSummary> content = queryFactory
                .select(Projections.constructor(
                        AdminReservationSummary.class,
                        reservation.id,
                        member.id,
                        member.email,
                        reservation.status,
                        reservation.totalAmount,
                        reservation.expiresAt,
                        reservation.createdAt,
                        reservationItem.id.countDistinct(),
                        reservationItem.quantity.sum()))
                .from(reservation)
                .join(reservation.member, member)
                .join(reservation.items, reservationItem)
                .join(reservationItem.inventory, sellableInventory)
                .join(sellableInventory.eventSession, eventSession)
                .join(eventSession.event, event)
                .where(reservationPredicates(condition))
                .groupBy(
                        reservation.id,
                        member.id,
                        member.email,
                        reservation.status,
                        reservation.totalAmount,
                        reservation.expiresAt,
                        reservation.createdAt)
                .orderBy(reservation.createdAt.desc(), reservation.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(reservation.id.countDistinct())
                .from(reservation)
                .join(reservation.items, reservationItem)
                .join(reservationItem.inventory, sellableInventory)
                .join(sellableInventory.eventSession, eventSession)
                .join(eventSession.event, event)
                .where(reservationPredicates(condition))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    public Page<AdminInventorySummary> searchInventory(
            AdminInventorySearchCondition condition,
            Pageable pageable
    ) {
        List<AdminInventorySummary> content = queryFactory
                .select(Projections.constructor(
                        AdminInventorySummary.class,
                        sellableInventory.id,
                        sellableInventory.type,
                        sellableInventory.name,
                        sellableInventory.price,
                        sellableInventory.totalQuantity,
                        sellableInventory.availableQuantity,
                        sellableInventory.totalQuantity.subtract(sellableInventory.availableQuantity),
                        eventSession.id,
                        eventSession.name,
                        eventSession.startsAt,
                        event.id,
                        event.title))
                .from(sellableInventory)
                .join(sellableInventory.eventSession, eventSession)
                .join(eventSession.event, event)
                .where(inventoryPredicates(condition))
                .orderBy(eventSession.startsAt.asc(), event.id.asc(), eventSession.id.asc(), sellableInventory.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(sellableInventory.count())
                .from(sellableInventory)
                .join(sellableInventory.eventSession, eventSession)
                .join(eventSession.event, event)
                .where(inventoryPredicates(condition))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    public List<AdminReservationSummary> searchReservationsByCursor(
            AdminReservationSearchCondition condition,
            ReservationCursor cursor,
            int limit
    ) {
        return queryFactory
                .select(Projections.constructor(
                        AdminReservationSummary.class,
                        reservation.id,
                        member.id,
                        member.email,
                        reservation.status,
                        reservation.totalAmount,
                        reservation.expiresAt,
                        reservation.createdAt,
                        reservationItem.id.countDistinct(),
                        reservationItem.quantity.sum()))
                .from(reservation)
                .join(reservation.member, member)
                .join(reservation.items, reservationItem)
                .join(reservationItem.inventory, sellableInventory)
                .join(sellableInventory.eventSession, eventSession)
                .join(eventSession.event, event)
                .where(reservationCursorPredicate(condition, cursor))
                .groupBy(
                        reservation.id,
                        member.id,
                        member.email,
                        reservation.status,
                        reservation.totalAmount,
                        reservation.expiresAt,
                        reservation.createdAt)
                .orderBy(reservation.createdAt.desc(), reservation.id.desc())
                .limit(limit)
                .fetch();
    }

    public ReservationOperationsSummary getReservationOperationsSummary() {
        List<Tuple> rows = queryFactory
                .select(reservation.status, reservation.count(), reservation.totalAmount.sum())
                .from(reservation)
                .groupBy(reservation.status)
                .fetch();

        Map<ReservationStatus, Long> counts = new EnumMap<>(ReservationStatus.class);
        for (ReservationStatus status : ReservationStatus.values()) {
            counts.put(status, 0L);
        }

        long total = 0;
        BigDecimal confirmedSales = BigDecimal.ZERO;
        for (Tuple row : rows) {
            ReservationStatus status = row.get(reservation.status);
            Long count = row.get(reservation.count());
            BigDecimal amount = row.get(reservation.totalAmount.sum());
            long safeCount = count == null ? 0 : count;
            counts.put(status, safeCount);
            total += safeCount;
            if (status == ReservationStatus.CONFIRMED && amount != null) {
                confirmedSales = amount;
            }
        }
        return new ReservationOperationsSummary(total, confirmedSales, Map.copyOf(counts));
    }

    private BooleanExpression[] reservationPredicates(AdminReservationSearchCondition condition) {
        return new BooleanExpression[] {
                reservationStatusEq(condition.status()),
                reservationMemberIdEq(condition.memberId()),
                reservationEventIdEq(condition.eventId()),
                reservationCreatedAtGoe(condition.createdFrom()),
                reservationCreatedAtLoe(condition.createdTo())
        };
    }

    private BooleanBuilder reservationCursorPredicate(
            AdminReservationSearchCondition condition,
            ReservationCursor cursor
    ) {
        BooleanBuilder predicate = new BooleanBuilder();
        for (BooleanExpression expression : reservationPredicates(condition)) {
            if (expression != null) {
                predicate.and(expression);
            }
        }
        predicate.and(reservationAfterCursor(cursor));
        return predicate;
    }

    private BooleanExpression[] inventoryPredicates(AdminInventorySearchCondition condition) {
        return new BooleanExpression[] {
                inventoryEventIdEq(condition.eventId()),
                inventorySessionIdEq(condition.eventSessionId()),
                inventoryTypeEq(condition.type()),
                inventoryAvailableLoe(condition.availableQuantityLoe()),
                inventorySoldOutEq(condition.soldOut())
        };
    }

    private BooleanExpression reservationStatusEq(ReservationStatus status) {
        return status == null ? null : reservation.status.eq(status);
    }

    private BooleanExpression reservationMemberIdEq(Long memberId) {
        return memberId == null ? null : reservation.member.id.eq(memberId);
    }

    private BooleanExpression reservationEventIdEq(Long eventId) {
        return eventId == null ? null : event.id.eq(eventId);
    }

    private BooleanExpression reservationCreatedAtGoe(Instant createdFrom) {
        return createdFrom == null ? null : reservation.createdAt.goe(createdFrom);
    }

    private BooleanExpression reservationCreatedAtLoe(Instant createdTo) {
        return createdTo == null ? null : reservation.createdAt.loe(createdTo);
    }

    private BooleanExpression reservationAfterCursor(ReservationCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return Expressions.booleanTemplate(
                "({0}, {1}) < ({2}, {3})",
                reservation.createdAt,
                reservation.id,
                cursor.createdAt(),
                cursor.reservationId());
    }

    private BooleanExpression inventoryEventIdEq(Long eventId) {
        return eventId == null ? null : event.id.eq(eventId);
    }

    private BooleanExpression inventorySessionIdEq(Long eventSessionId) {
        return eventSessionId == null ? null : eventSession.id.eq(eventSessionId);
    }

    private BooleanExpression inventoryTypeEq(InventoryType type) {
        return type == null ? null : sellableInventory.type.eq(type);
    }

    private BooleanExpression inventoryAvailableLoe(Integer quantity) {
        return quantity == null ? null : sellableInventory.availableQuantity.loe(quantity);
    }

    private BooleanExpression inventorySoldOutEq(Boolean soldOut) {
        if (soldOut == null) {
            return null;
        }
        return soldOut
                ? sellableInventory.availableQuantity.eq(0)
                : sellableInventory.availableQuantity.gt(0);
    }
}
