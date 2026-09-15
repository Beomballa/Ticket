package com.portfolio.fanevent.admin.api;

import com.portfolio.fanevent.admin.application.AdminCompensationService;
import com.portfolio.fanevent.admin.application.AdminCompensationSummary;
import com.portfolio.fanevent.support.api.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payment-compensations")
@Tag(name = "Admin Payment Compensations", description = "늦은 결제 승인 보상 환불 조회와 재처리")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
public class AdminCompensationController {

    private final AdminCompensationService service;

    public AdminCompensationController(AdminCompensationService service) {
        this.service = service;
    }

    @GetMapping
    public AdminQueryController.PageResponse<AdminCompensationSummary> search(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AdminCompensationSummary> page = service.search(pageable);
        return AdminQueryController.PageResponse.from(page);
    }

    @PostMapping("/{attemptId}/retry")
    public AdminCompensationSummary retry(
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        return service.retry(attemptId, authentication.getName());
    }
}
