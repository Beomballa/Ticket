package com.portfolio.fanevent.reservation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmReservationRequest(
        @NotBlank @Size(max = 100) String paymentToken
) {
}
