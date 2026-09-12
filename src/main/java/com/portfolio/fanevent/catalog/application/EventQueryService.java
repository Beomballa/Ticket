package com.portfolio.fanevent.catalog.application;

import com.portfolio.fanevent.catalog.infrastructure.EventQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventQueryService {

    private final EventQueryRepository eventQueryRepository;

    public EventQueryService(EventQueryRepository eventQueryRepository) {
        this.eventQueryRepository = eventQueryRepository;
    }

    public Page<EventSummary> search(EventSearchCondition condition, Pageable pageable) {
        return eventQueryRepository.search(condition, pageable);
    }

    public EventDetail getPublicDetail(Long eventId) {
        return eventQueryRepository.findPublicDetail(eventId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "공개 이벤트를 찾을 수 없습니다: " + eventId));
    }
}
