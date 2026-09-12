package com.portfolio.fanevent.reservation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateReservationRequest(
        @NotNull @Size(min = 1, max = 20) List<@Valid Item> items
) {

    public record Item(
            @NotNull Long inventoryId,
            @Min(1) int quantity
    ) {
    }
}
