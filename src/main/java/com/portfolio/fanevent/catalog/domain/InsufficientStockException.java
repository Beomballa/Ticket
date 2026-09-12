package com.portfolio.fanevent.catalog.domain;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long inventoryId, int requested, int available) {
        super("재고가 부족합니다. inventoryId=%d, requested=%d, available=%d"
                .formatted(inventoryId, requested, available));
    }
}
