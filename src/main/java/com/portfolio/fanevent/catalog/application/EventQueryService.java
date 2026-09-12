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
    private final PublicEventCache publicEventCache;

    public EventQueryService(EventQueryRepository eventQueryRepository, PublicEventCache publicEventCache) {
        this.eventQueryRepository = eventQueryRepository;
        this.publicEventCache = publicEventCache;
    }

    public Page<EventSummary> search(EventSearchCondition condition, Pageable pageable) {
        return eventQueryRepository.search(condition, pageable);
    }

    public EventDetail getPublicDetail(Long eventId) {
        return publicEventCache.get(eventId).orElseGet(() -> loadAndCache(eventId));
    }

    private EventDetail loadAndCache(Long eventId) {
        EventDetail detail = eventQueryRepository.findPublicDetail(eventId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "공개 이벤트를 찾을 수 없습니다: " + eventId));
        publicEventCache.put(eventId, detail);
        return detail;
    }
}
