package com.portfolio.fanevent.catalog.api;

import com.portfolio.fanevent.catalog.application.EventQueryService;
import com.portfolio.fanevent.catalog.application.EventSearchCondition;
import com.portfolio.fanevent.catalog.application.EventSummary;
import com.portfolio.fanevent.catalog.application.EventDetail;
import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventQueryController {

    private final EventQueryService eventQueryService;

    public EventQueryController(EventQueryService eventQueryService) {
        this.eventQueryService = eventQueryService;
    }

    @GetMapping
    public EventPageResponse search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long artistId,
            @RequestParam(required = false) EventType type,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant salesFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant salesTo,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<EventSummary> page = eventQueryService.search(
                new EventSearchCondition(keyword, artistId, type, status, salesFrom, salesTo),
                pageable);
        return new EventPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @GetMapping("/{eventId}")
    public EventDetail getDetail(@PathVariable Long eventId) {
        return eventQueryService.getPublicDetail(eventId);
    }

    public record EventPageResponse(
            List<EventSummary> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }
}
