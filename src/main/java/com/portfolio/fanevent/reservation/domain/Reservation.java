package com.portfolio.fanevent.reservation.domain;

import com.portfolio.fanevent.catalog.domain.SellableInventory;
import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.support.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "reservations")
public class Reservation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationItem> items = new ArrayList<>();

    protected Reservation() {
    }

    private Reservation(Member member, Instant expiresAt) {
        if (member == null || expiresAt == null) {
            throw new IllegalArgumentException("예약 필수값이 누락되었습니다.");
        }
        this.member = member;
        this.status = ReservationStatus.PENDING;
        this.totalAmount = BigDecimal.ZERO;
        this.expiresAt = expiresAt;
    }

    public static Reservation pending(Member member, Instant expiresAt) {
        return new Reservation(member, expiresAt);
    }

    public void addItem(SellableInventory inventory, int quantity) {
        ReservationItem item = ReservationItem.create(this, inventory, quantity, inventory.getPrice());
        items.add(item);
        totalAmount = totalAmount.add(item.getLineAmount());
    }

    public void requireConfirmable(Instant now) {
        if (status == ReservationStatus.CONFIRMED) {
            return;
        }
        requireTransitionTo(ReservationStatus.CONFIRMED);
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStateException("선점 시간이 만료된 예약은 확정할 수 없습니다.");
        }
    }

    public boolean confirm(Instant now) {
        requireConfirmable(now);
        if (status == ReservationStatus.CONFIRMED) {
            return false;
        }
        status = ReservationStatus.CONFIRMED;
        confirmedAt = now;
        return true;
    }

    public void requireCancellable() {
        requireTransitionTo(ReservationStatus.CANCELLED);
    }

    public boolean cancel(Instant now) {
        requireCancellable();
        if (status == ReservationStatus.CANCELLED) {
            return false;
        }
        status = ReservationStatus.CANCELLED;
        cancelledAt = now;
        return true;
    }

    public boolean expire(Instant now) {
        if (status == ReservationStatus.EXPIRED) {
            return false;
        }
        requireTransitionTo(ReservationStatus.EXPIRED);
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("선점 시간이 남은 예약은 만료할 수 없습니다.");
        }
        status = ReservationStatus.EXPIRED;
        expiredAt = now;
        return true;
    }

    public boolean isConfirmed() {
        return status == ReservationStatus.CONFIRMED;
    }

    public boolean isCancelled() {
        return status == ReservationStatus.CANCELLED;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<ReservationItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    private void requireTransitionTo(ReservationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "허용되지 않은 예약 상태 전이입니다: " + status + " -> " + target);
        }
    }
}
