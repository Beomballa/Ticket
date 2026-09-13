package com.portfolio.fanevent.admin.api;

import com.portfolio.fanevent.admin.application.AdminOutboxEventSummary;
import com.portfolio.fanevent.admin.application.AdminOutboxSearchCondition;
import com.portfolio.fanevent.admin.application.AdminOutboxService;
import com.portfolio.fanevent.admin.application.OutboxRetryResult;
import com.portfolio.fanevent.outbox.domain.OutboxStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbox-events")
public class AdminOutboxController {

    private final AdminOutboxService adminOutboxService;

    public AdminOutboxController(AdminOutboxService adminOutboxService) {
        this.adminOutboxService = adminOutboxService;
    }

    @GetMapping
    public AdminQueryController.PageResponse<AdminOutboxEventSummary> search(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(required = false) Integer attemptsGoe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AdminOutboxEventSummary> page = adminOutboxService.search(
                new AdminOutboxSearchCondition(
                        status, eventType, aggregateId, attemptsGoe, createdFrom, createdTo),
                pageable);
        return AdminQueryController.PageResponse.from(page);
    }

    @GetMapping("/exhausted")
    public AdminQueryController.PageResponse<AdminOutboxEventSummary> searchExhaustedFailures(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return AdminQueryController.PageResponse.from(
                adminOutboxService.searchExhaustedFailures(pageable));
    }

    @PostMapping("/{eventId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OutboxRetryResult retry(
            @PathVariable UUID eventId,
            Authentication authentication
    ) {
        return adminOutboxService.retryExhaustedFailure(eventId, authentication.getName());
    }
}
