package com.portfolio.fanevent.reservation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.reservation.expiration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationScheduler.class);

    private final ReservationExpirationService expirationService;
    private final ReservationExpirationProperties properties;

    public ReservationExpirationScheduler(
            ReservationExpirationService expirationService,
            ReservationExpirationProperties properties
    ) {
        this.expirationService = expirationService;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${app.reservation.expiration.initial-delay:PT10S}",
            fixedDelayString = "${app.reservation.expiration.fixed-delay:PT1S}")
    public void expireReservations() {
        int expired = expirationService.expireNextBatch(properties.batchSize());
        if (expired > 0) {
            log.info("Expired reservations: count={}", expired);
        }
    }
}
