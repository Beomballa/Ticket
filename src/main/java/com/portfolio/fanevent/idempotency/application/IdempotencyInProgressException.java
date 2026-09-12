package com.portfolio.fanevent.idempotency.application;

public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException() {
        super("같은 멱등키의 요청이 처리 중입니다.");
    }
}
