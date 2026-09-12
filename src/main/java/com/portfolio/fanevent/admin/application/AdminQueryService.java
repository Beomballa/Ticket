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
    private final ReservationCursorCodec reservationCursorCodec;

    public AdminQueryService(
            AdminQueryRepository adminQueryRepository,
            ReservationCursorCodec reservationCursorCodec
    ) {
        this.adminQueryRepository = adminQueryRepository;
        this.reservationCursorCodec = reservationCursorCodec;
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

    public CursorPage<AdminReservationSummary> searchReservationsByCursor(
            AdminReservationSearchCondition condition,
            String encodedCursor,
            int size
    ) {
        validatePeriod(condition.createdFrom(), condition.createdTo());
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("커서 페이지 크기는 1 이상 100 이하여야 합니다.");
        }

        ReservationCursor cursor = reservationCursorCodec.decode(encodedCursor);
        java.util.List<AdminReservationSummary> rows =
                adminQueryRepository.searchReservationsByCursor(condition, cursor, size + 1);
        boolean hasNext = rows.size() > size;
        java.util.List<AdminReservationSummary> content = hasNext
                ? rows.subList(0, size)
                : rows;
        String nextCursor = null;
        if (hasNext) {
            AdminReservationSummary last = content.get(content.size() - 1);
            nextCursor = reservationCursorCodec.encode(
                    new ReservationCursor(last.createdAt(), last.reservationId()));
        }
        return new CursorPage<>(content, nextCursor, hasNext);
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
