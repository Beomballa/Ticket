package com.portfolio.fanevent.reservation.domain;

import com.portfolio.fanevent.catalog.domain.SellableInventory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "reservation_items")
@EntityListeners(AuditingEntityListener.class)
public class ReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_id", nullable = false)
    private SellableInventory inventory;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReservationItem() {
    }

    private ReservationItem(
            Reservation reservation,
            SellableInventory inventory,
            int quantity,
            BigDecimal unitPrice
    ) {
        if (reservation == null || inventory == null || unitPrice == null || quantity <= 0) {
            throw new IllegalArgumentException("예약 항목 값이 올바르지 않습니다.");
        }
        this.reservation = reservation;
        this.inventory = inventory;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    static ReservationItem create(
            Reservation reservation,
            SellableInventory inventory,
            int quantity,
            BigDecimal unitPrice
    ) {
        return new ReservationItem(reservation, inventory, quantity, unitPrice);
    }

    public BigDecimal getLineAmount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getInventoryId() {
        return inventory.getId();
    }

    public String getInventoryName() {
        return inventory.getName();
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void releaseInventory() {
        inventory.release(quantity);
    }
}
