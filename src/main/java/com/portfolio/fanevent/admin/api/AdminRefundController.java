package com.portfolio.fanevent.admin.api;

import com.portfolio.fanevent.admin.application.AdminRefundAttemptSummary;
import com.portfolio.fanevent.admin.application.AdminRefundService;
import com.portfolio.fanevent.admin.application.RefundReconcileResult;
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
@RequestMapping("/api/admin/refund-attempts")
@Tag(name = "Admin Refunds", description = "환불 결과 불명 시도 조회와 대사")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
public class AdminRefundController {

    private final AdminRefundService adminRefundService;

    public AdminRefundController(AdminRefundService adminRefundService) {
        this.adminRefundService = adminRefundService;
    }

    @GetMapping("/unknown")
    public AdminQueryController.PageResponse<AdminRefundAttemptSummary> searchUnknown(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AdminRefundAttemptSummary> page = adminRefundService.searchUnknown(pageable);
        return AdminQueryController.PageResponse.from(page);
    }

    @PostMapping("/{refundAttemptId}/reconcile")
    public RefundReconcileResult reconcile(
            @PathVariable UUID refundAttemptId,
            Authentication authentication
    ) {
        return adminRefundService.reconcile(refundAttemptId, authentication.getName());
    }
}
