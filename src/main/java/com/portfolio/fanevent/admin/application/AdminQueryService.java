package com.portfolio.fanevent.admin.application;

import com.portfolio.fanevent.admin.infrastructure.AdminQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminQueryService {

    private final AdminQueryRepository adminQueryRepository;

    public AdminQueryService(AdminQueryRepository adminQueryRepository) {
        this.adminQueryRepository = adminQueryRepository;
    }

    public Page<AdminReservationSummary> searchReservations(
            AdminReservationSearchCondition condition,
            Pageable pageable
    ) {
        validatePeriod(condition.createdFrom(), condition.createdTo());
        return adminQueryRepository.searchReservations(condition, pageable);
    }

    public Page<AdminInventorySummary> searchInventory(
            AdminInventorySearchCondition condition,
            Pageable pageable
    ) {
        if (condition.availableQuantityLoe() != null && condition.availableQuantityLoe() < 0) {
            throw new IllegalArgumentException("재고 상한은 0 이상이어야 합니다.");
        }
        return adminQueryRepository.searchInventory(condition, pageable);
    }

    public ReservationOperationsSummary getReservationOperationsSummary() {
        return adminQueryRepository.getReservationOperationsSummary();
    }

    private void validatePeriod(java.time.Instant from, java.time.Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작 시각은 종료 시각보다 늦을 수 없습니다.");
        }
    }
}
