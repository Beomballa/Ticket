package com.portfolio.fanevent.admin.api;

import com.portfolio.fanevent.admin.application.AdminInventorySearchCondition;
import com.portfolio.fanevent.admin.application.AdminInventorySummary;
import com.portfolio.fanevent.admin.application.AdminQueryService;
import com.portfolio.fanevent.admin.application.AdminReservationSearchCondition;
import com.portfolio.fanevent.admin.application.AdminReservationSummary;
import com.portfolio.fanevent.admin.application.ReservationOperationsSummary;
import com.portfolio.fanevent.catalog.domain.InventoryType;
import com.portfolio.fanevent.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminQueryController {

    private final AdminQueryService adminQueryService;

    public AdminQueryController(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @GetMapping("/reservations")
    public PageResponse<AdminReservationSummary> searchReservations(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AdminReservationSummary> page = adminQueryService.searchReservations(
                new AdminReservationSearchCondition(status, memberId, eventId, createdFrom, createdTo),
                pageable);
        return PageResponse.from(page);
    }

    @GetMapping("/inventory")
    public PageResponse<AdminInventorySummary> searchInventory(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) Long eventSessionId,
            @RequestParam(required = false) InventoryType type,
            @RequestParam(required = false) Integer availableQuantityLoe,
            @RequestParam(required = false) Boolean soldOut,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AdminInventorySummary> page = adminQueryService.searchInventory(
                new AdminInventorySearchCondition(
                        eventId, eventSessionId, type, availableQuantityLoe, soldOut),
                pageable);
        return PageResponse.from(page);
    }

    @GetMapping("/reservations/summary")
    public ReservationOperationsSummary getReservationOperationsSummary() {
        return adminQueryService.getReservationOperationsSummary();
    }

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        static <T> PageResponse<T> from(Page<T> page) {
            return new PageResponse<>(
                    page.getContent(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }
}
