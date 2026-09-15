package com.portfolio.fanevent.waitingroom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WaitingRoomScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomScheduler.class);
    private final WaitingRoomService service;
    private final WaitingRoomProperties properties;

    public WaitingRoomScheduler(WaitingRoomService service, WaitingRoomProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${app.waiting-room.initial-delay:PT10S}",
            fixedDelayString = "${app.waiting-room.fixed-delay:PT1S}")
    public void admitWaitingMembers() {
        if (!properties.enabled()) return;
        try {
            service.reconcileRuntime();
            service.summaries().stream()
                    .filter(WaitingRoomSummary::enabled)
                    .forEach(summary -> service.admit(summary.eventId()));
        } catch (WaitingRoomUnavailableException exception) {
            log.warn("Redis 대기열 자동 입장을 다음 주기로 연기합니다.");
        }
    }
}
