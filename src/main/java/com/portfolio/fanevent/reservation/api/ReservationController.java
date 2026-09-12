package com.portfolio.fanevent.reservation.api;

import com.portfolio.fanevent.reservation.application.ReservationCommandService;
import com.portfolio.fanevent.reservation.application.ReservationItemCommand;
import com.portfolio.fanevent.reservation.application.ReservationResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationCommandService reservationCommandService;

    public ReservationController(ReservationCommandService reservationCommandService) {
        this.reservationCommandService = reservationCommandService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReservationResult hold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        return reservationCommandService.hold(
                Long.valueOf(jwt.getSubject()),
                request.items().stream()
                        .map(item -> new ReservationItemCommand(item.inventoryId(), item.quantity()))
                        .toList(),
                idempotencyKey);
    }

    @PostMapping("/{reservationId}/confirm")
    ReservationResult confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long reservationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConfirmReservationRequest request
    ) {
        return reservationCommandService.confirm(
                Long.valueOf(jwt.getSubject()),
                reservationId,
                request.paymentToken(),
                idempotencyKey);
    }

    @PostMapping("/{reservationId}/cancel")
    ReservationResult cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long reservationId
    ) {
        return reservationCommandService.cancel(Long.valueOf(jwt.getSubject()), reservationId);
    }
}
