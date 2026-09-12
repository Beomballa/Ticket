package com.portfolio.fanevent.catalog.domain;

import com.portfolio.fanevent.support.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "events")
public class Event extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artist_id", nullable = false)
    private Artist artist;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private EventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventStatus status;

    @Column(name = "sales_start_at", nullable = false)
    private Instant salesStartAt;

    @Column(name = "sales_end_at", nullable = false)
    private Instant salesEndAt;

    protected Event() {
    }

    private Event(
            Artist artist,
            String title,
            String description,
            EventType type,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        this.artist = requireNonNull(artist, "아티스트");
        this.title = requireText(title, "이벤트 제목");
        this.description = description;
        this.type = requireNonNull(type, "이벤트 유형");
        this.status = EventStatus.DRAFT;
        validateSalesPeriod(salesStartAt, salesEndAt);
        this.salesStartAt = salesStartAt;
        this.salesEndAt = salesEndAt;
    }

    public static Event create(
            Artist artist,
            String title,
            String description,
            EventType type,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        return new Event(artist, title, description, type, salesStartAt, salesEndAt);
    }

    public void update(
            String title,
            String description,
            EventType type,
            Instant salesStartAt,
            Instant salesEndAt
    ) {
        requireCatalogEditable();
        validateSalesPeriod(salesStartAt, salesEndAt);
        this.title = requireText(title, "이벤트 제목");
        this.description = description;
        this.type = requireNonNull(type, "이벤트 유형");
        this.salesStartAt = salesStartAt;
        this.salesEndAt = salesEndAt;
    }

    public void changeStatus(EventStatus targetStatus) {
        EventStatus target = requireNonNull(targetStatus, "변경할 이벤트 상태");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "허용되지 않은 이벤트 상태 전이입니다: " + status + " -> " + target);
        }
        this.status = target;
    }

    public void requireCatalogEditable() {
        if (!status.isCatalogEditable()) {
            throw new IllegalStateException("판매 시작 이후에는 카탈로그 정보를 수정할 수 없습니다.");
        }
    }

    public void requireOnSale(Instant now) {
        if (status != EventStatus.ON_SALE) {
            throw new IllegalStateException("판매 중인 이벤트만 예약할 수 있습니다.");
        }
        if (now.isBefore(salesStartAt) || !now.isBefore(salesEndAt)) {
            throw new IllegalStateException("이벤트 판매 가능 시간이 아닙니다.");
        }
    }

    public Artist getArtist() {
        return artist;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public EventType getType() {
        return type;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getSalesStartAt() {
        return salesStartAt;
    }

    public Instant getSalesEndAt() {
        return salesEndAt;
    }

    private static void validateSalesPeriod(Instant startAt, Instant endAt) {
        requireNonNull(startAt, "판매 시작 시각");
        requireNonNull(endAt, "판매 종료 시각");
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("판매 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
        }
        return value.trim();
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
        }
        return value;
    }
}
