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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sellable_inventory")
public class SellableInventory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_session_id", nullable = false)
    private EventSession eventSession;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_type", nullable = false, length = 30)
    private InventoryType type;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Version
    @Column(nullable = false)
    private long version;

    protected SellableInventory() {
    }

    private SellableInventory(
            EventSession eventSession,
            InventoryType type,
            String name,
            BigDecimal price,
            int quantity
    ) {
        if (eventSession == null) {
            throw new IllegalArgumentException("재고 필수값이 누락되었습니다.");
        }
        validate(type, name, price, quantity);
        this.eventSession = eventSession;
        this.type = type;
        this.name = name.trim();
        this.price = price;
        this.totalQuantity = quantity;
        this.availableQuantity = quantity;
    }

    public static SellableInventory create(
            EventSession eventSession,
            InventoryType type,
            String name,
            BigDecimal price,
            int quantity
    ) {
        return new SellableInventory(eventSession, type, name, price, quantity);
    }

    public void update(InventoryType type, String name, BigDecimal price, int quantity) {
        eventSession.getEvent().requireCatalogEditable();
        validate(type, name, price, quantity);
        this.type = type;
        this.name = name.trim();
        this.price = price;
        this.totalQuantity = quantity;
        this.availableQuantity = quantity;
    }

    public void reserve(int quantity, Instant now) {
        requireReservable(quantity, now);
        if (availableQuantity < quantity) {
            throw new InsufficientStockException(getId(), quantity, availableQuantity);
        }
        availableQuantity -= quantity;
    }

    public void requireReservable(int quantity, Instant now) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("예약 수량은 1 이상이어야 합니다.");
        }
        eventSession.requireOnSale(now);
    }

    public void release(int quantity) {
        if (quantity <= 0 || availableQuantity + quantity > totalQuantity) {
            throw new IllegalStateException("반환할 재고 수량이 올바르지 않습니다.");
        }
        availableQuantity += quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getName() {
        return name;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    private static void validate(InventoryType type, String name, BigDecimal price, int quantity) {
        if (type == null || name == null || name.isBlank() || price == null) {
            throw new IllegalArgumentException("재고 필수값이 누락되었습니다.");
        }
        if (price.signum() < 0 || quantity < 0) {
            throw new IllegalArgumentException("가격과 수량은 0 이상이어야 합니다.");
        }
    }
}
