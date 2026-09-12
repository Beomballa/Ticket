package com.portfolio.fanevent.catalog.domain;

import com.portfolio.fanevent.support.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "event_sessions")
public class EventSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "sales_start_at", nullable = false)
    private Instant salesStartAt;

    @Column(name = "sales_end_at", nullable = false)
    private Instant salesEndAt;

    protected EventSession() {
    }

    private EventSession(
            Event event,
            String name,
            String venue,
            Instant startsAt,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        if (event == null) {
            throw new IllegalArgumentException("회차 필수값이 누락되었습니다.");
        }
        validate(name, startsAt, salesStartAt, salesEndAt);
        this.event = event;
        this.name = name.trim();
        this.venue = venue;
        this.startsAt = startsAt;
        this.salesStartAt = salesStartAt;
        this.salesEndAt = salesEndAt;
    }

    public static EventSession create(
            Event event,
            String name,
            String venue,
            Instant startsAt,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        return new EventSession(event, name, venue, startsAt, salesStartAt, salesEndAt);
    }

    public void update(
            String name,
            String venue,
            Instant startsAt,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        event.requireCatalogEditable();
        validate(name, startsAt, salesStartAt, salesEndAt);
        this.name = name.trim();
        this.venue = venue;
        this.startsAt = startsAt;
        this.salesStartAt = salesStartAt;
        this.salesEndAt = salesEndAt;
    }

    public Event getEvent() {
        return event;
    }

    public void requireOnSale(Instant now) {
        event.requireOnSale(now);
        if (now.isBefore(salesStartAt) || !now.isBefore(salesEndAt)) {
            throw new IllegalStateException("회차 판매 가능 시간이 아닙니다.");
        }
    }

    private static void validate(
            String name,
            Instant startsAt,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        if (name == null || name.isBlank() || startsAt == null
                || salesStartAt == null || salesEndAt == null) {
            throw new IllegalArgumentException("회차 필수값이 누락되었습니다.");
        }
        if (!salesStartAt.isBefore(salesEndAt)) {
            throw new IllegalArgumentException("회차 판매 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
    }
}
