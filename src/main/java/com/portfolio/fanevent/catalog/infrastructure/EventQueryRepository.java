package com.portfolio.fanevent.catalog.infrastructure;

import static com.portfolio.fanevent.catalog.domain.QArtist.artist;
import static com.portfolio.fanevent.catalog.domain.QEvent.event;
import static com.portfolio.fanevent.catalog.domain.QEventSession.eventSession;
import static com.portfolio.fanevent.catalog.domain.QSellableInventory.sellableInventory;

import com.portfolio.fanevent.catalog.application.EventDetail;
import com.portfolio.fanevent.catalog.application.EventSearchCondition;
import com.portfolio.fanevent.catalog.application.EventSummary;
import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class EventQueryRepository {

    private final JPAQueryFactory queryFactory;

    public EventQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<EventSummary> search(EventSearchCondition condition, Pageable pageable) {
        List<EventSummary> content = queryFactory
                .select(Projections.constructor(
                        EventSummary.class,
                        event.id,
                        event.title,
                        event.type,
                        event.status,
                        artist.id,
                        artist.name,
                        event.salesStartAt,
                        event.salesEndAt))
                .from(event)
                .join(event.artist, artist)
                .where(
                        publicStatus(condition.status()),
                        titleContains(condition.keyword()),
                        artistIdEq(condition.artistId()),
                        typeEq(condition.type()),
                        salesStartGoe(condition.salesFrom()),
                        salesEndLoe(condition.salesTo()))
                .orderBy(event.salesStartAt.desc(), event.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(event.count())
                .from(event)
                .where(
                        publicStatus(condition.status()),
                        titleContains(condition.keyword()),
                        artistIdEq(condition.artistId()),
                        typeEq(condition.type()),
                        salesStartGoe(condition.salesFrom()),
                        salesEndLoe(condition.salesTo()))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    public Optional<EventDetail> findPublicDetail(Long eventId) {
        Tuple header = queryFactory
                .select(
                        event.id,
                        event.title,
                        event.description,
                        event.type,
                        event.status,
                        artist.id,
                        artist.name,
                        event.salesStartAt,
                        event.salesEndAt)
                .from(event)
                .join(event.artist, artist)
                .where(event.id.eq(eventId), publicStatus(null))
                .fetchOne();

        if (header == null) {
            return Optional.empty();
        }

        List<Tuple> rows = queryFactory
                .select(
                        eventSession.id,
                        eventSession.name,
                        eventSession.venue,
                        eventSession.startsAt,
                        eventSession.salesStartAt,
                        eventSession.salesEndAt,
                        sellableInventory.id,
                        sellableInventory.type,
                        sellableInventory.name,
                        sellableInventory.price,
                        sellableInventory.totalQuantity,
                        sellableInventory.availableQuantity)
                .from(eventSession)
                .leftJoin(sellableInventory)
                .on(sellableInventory.eventSession.eq(eventSession))
                .where(eventSession.event.id.eq(eventId))
                .orderBy(eventSession.startsAt.asc(), eventSession.id.asc(), sellableInventory.id.asc())
                .fetch();

        Map<Long, SessionAccumulator> sessions = new LinkedHashMap<>();
        for (Tuple row : rows) {
            Long sessionId = row.get(eventSession.id);
            SessionAccumulator session = sessions.computeIfAbsent(sessionId, ignored -> new SessionAccumulator(
                    sessionId,
                    row.get(eventSession.name),
                    row.get(eventSession.venue),
                    row.get(eventSession.startsAt),
                    row.get(eventSession.salesStartAt),
                    row.get(eventSession.salesEndAt)));

            Long inventoryId = row.get(sellableInventory.id);
            if (inventoryId != null) {
                session.inventory.add(new EventDetail.InventoryDetail(
                        inventoryId,
                        row.get(sellableInventory.type),
                        row.get(sellableInventory.name),
                        row.get(sellableInventory.price),
                        row.get(sellableInventory.totalQuantity),
                        row.get(sellableInventory.availableQuantity)));
            }
        }

        List<EventDetail.SessionDetail> sessionDetails = sessions.values().stream()
                .map(SessionAccumulator::toDetail)
                .toList();

        return Optional.of(new EventDetail(
                header.get(event.id),
                header.get(event.title),
                header.get(event.description),
                header.get(event.type),
                header.get(event.status),
                header.get(artist.id),
                header.get(artist.name),
                header.get(event.salesStartAt),
                header.get(event.salesEndAt),
                sessionDetails));
    }

    private BooleanExpression publicStatus(EventStatus requested) {
        if (requested != null) {
            return event.status.in(EventStatus.PUBLISHED, EventStatus.ON_SALE)
                    .and(event.status.eq(requested));
        }
        return event.status.in(EventStatus.PUBLISHED, EventStatus.ON_SALE);
    }

    private BooleanExpression titleContains(String keyword) {
        return keyword == null || keyword.isBlank()
                ? null
                : event.title.containsIgnoreCase(keyword.trim());
    }

    private BooleanExpression artistIdEq(Long artistId) {
        return artistId == null ? null : event.artist.id.eq(artistId);
    }

    private BooleanExpression typeEq(EventType type) {
        return type == null ? null : event.type.eq(type);
    }

    private BooleanExpression salesStartGoe(Instant salesFrom) {
        return salesFrom == null ? null : event.salesStartAt.goe(salesFrom);
    }

    private BooleanExpression salesEndLoe(Instant salesTo) {
        return salesTo == null ? null : event.salesEndAt.loe(salesTo);
    }

    private static final class SessionAccumulator {

        private final Long id;
        private final String name;
        private final String venue;
        private final Instant startsAt;
        private final Instant salesStartAt;
        private final Instant salesEndAt;
        private final List<EventDetail.InventoryDetail> inventory = new ArrayList<>();

        private SessionAccumulator(
                Long id,
                String name,
                String venue,
                Instant startsAt,
                Instant salesStartAt,
                Instant salesEndAt
        ) {
            this.id = id;
            this.name = name;
            this.venue = venue;
            this.startsAt = startsAt;
            this.salesStartAt = salesStartAt;
            this.salesEndAt = salesEndAt;
        }

        private EventDetail.SessionDetail toDetail() {
            return new EventDetail.SessionDetail(
                    id, name, venue, startsAt, salesStartAt, salesEndAt, List.copyOf(inventory));
        }
    }
}
