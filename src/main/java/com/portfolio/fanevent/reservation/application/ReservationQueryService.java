package com.portfolio.fanevent.reservation.application;

import com.portfolio.fanevent.reservation.infrastructure.ReservationQueryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReservationQueryService {

    private final ReservationQueryRepository reservationQueryRepository;

    public ReservationQueryService(ReservationQueryRepository reservationQueryRepository) {
        this.reservationQueryRepository = reservationQueryRepository;
    }

    public Page<MemberReservationSummary> search(
            Long memberId,
            MemberReservationSearchCondition condition,
            Pageable pageable
    ) {
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new IllegalArgumentException("예약 목록 크기는 1 이상 100 이하여야 합니다.");
        }
        if (condition.createdFrom() != null && condition.createdTo() != null
                && condition.createdFrom().isAfter(condition.createdTo())) {
            throw new IllegalArgumentException("조회 시작 시각은 종료 시각보다 늦을 수 없습니다.");
        }
        return reservationQueryRepository.search(memberId, condition, pageable);
    }

    public MemberReservationDetail getDetail(Long memberId, Long reservationId) {
        return reservationQueryRepository.findOwnedDetail(memberId, reservationId)
                .orElseThrow(() -> new EntityNotFoundException("예약을 찾을 수 없습니다."));
    }
}
