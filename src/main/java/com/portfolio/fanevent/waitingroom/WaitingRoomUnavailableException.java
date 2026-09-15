package com.portfolio.fanevent.waitingroom;

public class WaitingRoomUnavailableException extends RuntimeException {
    public WaitingRoomUnavailableException() {
        super("대기열 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
