package com.portfolio.fanevent.reservation.api;

import com.portfolio.fanevent.reservation.application.ReservationCommandService;
import com.portfolio.fanevent.reservation.application.ReservationItemCommand;
import com.portfolio.fanevent.reservation.application.MemberReservationDetail;
import com.portfolio.fanevent.reservation.application.MemberReservationSearchCondition;
import com.portfolio.fanevent.reservation.application.MemberReservationSummary;
import com.portfolio.fanevent.reservation.application.ReservationQueryService;
import com.portfolio.fanevent.reservation.application.ReservationRateLimiter;
import com.portfolio.fanevent.reservation.application.ReservationResult;
import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationCommandService reservationCommandService;
    private final ReservationQueryService reservationQueryService;
    private final ReservationRateLimiter reservationRateLimiter;

    public ReservationController(
            ReservationCommandService reservationCommandService,
            ReservationQueryService reservationQueryService,
            ReservationRateLimiter reservationRateLimiter
    ) {
        this.reservationCommandService = reservationCommandService;
        this.reservationQueryService = reservationQueryService;
        this.reservationRateLimiter = reservationRateLimiter;
    }

    @GetMapping
    ReservationPageResponse<MemberReservationSummary> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ReservationPageResponse.from(reservationQueryService.search(
                Long.valueOf(jwt.getSubject()),
                new MemberReservationSearchCondition(status, createdFrom, createdTo),
                pageable));
    }

    @GetMapping("/{reservationId}")
    MemberReservationDetail detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long reservationId
    ) {
        return reservationQueryService.getDetail(Long.valueOf(jwt.getSubject()), reservationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReservationResult hold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        Long memberId = Long.valueOf(jwt.getSubject());
        reservationRateLimiter.check(memberId);
        return reservationCommandService.hold(
                memberId,
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
