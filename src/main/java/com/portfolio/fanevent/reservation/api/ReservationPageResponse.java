package com.portfolio.fanevent.reservation.api;

import java.util.List;
import org.springframework.data.domain.Page;

public record ReservationPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    static <T> ReservationPageResponse<T> from(Page<T> page) {
        return new ReservationPageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
