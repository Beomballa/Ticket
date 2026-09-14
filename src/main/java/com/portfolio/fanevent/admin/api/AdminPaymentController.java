package com.portfolio.fanevent.admin.api;

import com.portfolio.fanevent.admin.application.AdminPaymentAttemptSummary;
import com.portfolio.fanevent.admin.application.AdminPaymentService;
import com.portfolio.fanevent.admin.application.PaymentReconcileResult;
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
@RequestMapping("/api/admin/payment-attempts")
@Tag(name = "Admin Payments", description = "결제 결과 불명 시도 조회와 대사")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
public class AdminPaymentController {

    private final AdminPaymentService adminPaymentService;

    public AdminPaymentController(AdminPaymentService adminPaymentService) {
        this.adminPaymentService = adminPaymentService;
    }

    @GetMapping("/unknown")
    public AdminQueryController.PageResponse<AdminPaymentAttemptSummary> searchUnknown(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AdminPaymentAttemptSummary> page = adminPaymentService.searchUnknown(pageable);
        return AdminQueryController.PageResponse.from(page);
    }

    @PostMapping("/{paymentAttemptId}/reconcile")
    public PaymentReconcileResult reconcile(
            @PathVariable UUID paymentAttemptId,
            Authentication authentication
    ) {
        return adminPaymentService.reconcile(paymentAttemptId, authentication.getName());
    }
}
