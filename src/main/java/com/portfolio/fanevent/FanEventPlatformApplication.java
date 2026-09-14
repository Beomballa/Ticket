package com.portfolio.fanevent;

import com.portfolio.fanevent.idempotency.application.IdempotencyProperties;
import com.portfolio.fanevent.catalog.application.PublicEventCacheProperties;
import com.portfolio.fanevent.outbox.application.OutboxProperties;
import com.portfolio.fanevent.payment.application.ReconciliationProperties;
import com.portfolio.fanevent.reservation.application.ReservationExpirationProperties;
import com.portfolio.fanevent.reservation.application.ReservationProperties;
import com.portfolio.fanevent.reservation.application.ReservationRateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
		ReservationProperties.class,
		ReservationExpirationProperties.class,
		ReservationRateLimitProperties.class,
		PublicEventCacheProperties.class,
		IdempotencyProperties.class,
		OutboxProperties.class,
		ReconciliationProperties.class
})
public class FanEventPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(FanEventPlatformApplication.class, args);
	}

}
