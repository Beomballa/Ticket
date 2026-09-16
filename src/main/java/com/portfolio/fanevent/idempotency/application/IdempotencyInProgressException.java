package com.portfolio.fanevent.idempotency.application;

public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException() {
        super("같은 작업 범위의 멱등 요청이 처리 중입니다.");
    }
}
