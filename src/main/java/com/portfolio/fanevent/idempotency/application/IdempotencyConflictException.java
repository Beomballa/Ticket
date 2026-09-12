package com.portfolio.fanevent.idempotency.application;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("같은 멱등키가 다른 요청에 사용되었습니다.");
    }
}
