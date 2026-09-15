package com.portfolio.fanevent.waitingroom;

import com.portfolio.fanevent.catalog.domain.Event;
import com.portfolio.fanevent.support.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;

@Entity
@Table(name = "event_waiting_room_policies")
public class WaitingRoomPolicy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, unique = true)
    private Event event;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "batch_size", nullable = false)
    private int batchSize;

    @Column(name = "active_capacity", nullable = false)
    private int activeCapacity;

    @Column(name = "admission_ttl_seconds", nullable = false)
    private long admissionTtlSeconds;

    @Version
    @Column(nullable = false)
    private long version;

    protected WaitingRoomPolicy() {
    }

    private WaitingRoomPolicy(Event event, int batchSize, int activeCapacity, Duration admissionTtl) {
        this.event = event;
        update(true, batchSize, activeCapacity, admissionTtl);
    }

    public static WaitingRoomPolicy enabled(
            Event event,
            int batchSize,
            int activeCapacity,
            Duration admissionTtl
    ) {
        if (event == null) throw new IllegalArgumentException("이벤트는 필수입니다.");
        return new WaitingRoomPolicy(event, batchSize, activeCapacity, admissionTtl);
    }

    public void update(boolean enabled, int batchSize, int activeCapacity, Duration admissionTtl) {
        validate(batchSize, activeCapacity, admissionTtl);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.activeCapacity = activeCapacity;
        this.admissionTtlSeconds = admissionTtl.toSeconds();
    }

    public void disable() {
        enabled = false;
    }

    private static void validate(int batchSize, int activeCapacity, Duration admissionTtl) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("입장 배치 크기는 1~10000이어야 합니다.");
        }
        if (activeCapacity < 1 || activeCapacity > 100_000) {
            throw new IllegalArgumentException("활성 입장 정원은 1~100000이어야 합니다.");
        }
        if (batchSize > activeCapacity) {
            throw new IllegalArgumentException("입장 배치 크기는 활성 입장 정원보다 클 수 없습니다.");
        }
        if (admissionTtl == null || admissionTtl.compareTo(Duration.ofSeconds(10)) < 0
                || admissionTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("입장 토큰 TTL은 10초~1시간이어야 합니다.");
        }
    }

    public Long getEventId() { return event.getId(); }
    public boolean isEnabled() { return enabled; }
    public int getBatchSize() { return batchSize; }
    public int getActiveCapacity() { return activeCapacity; }
    public Duration getAdmissionTtl() { return Duration.ofSeconds(admissionTtlSeconds); }
}
