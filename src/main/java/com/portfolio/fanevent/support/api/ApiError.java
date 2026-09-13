package com.portfolio.fanevent.support.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "모든 API 오류가 공유하는 응답 계약")
public record ApiError(
        @Schema(description = "클라이언트가 분기 처리할 안정적인 오류 코드", example = "VALIDATION_ERROR")
        String code,
        @Schema(description = "사용자에게 전달할 수 있는 오류 설명")
        String message,
        @Schema(description = "로그와 메트릭을 연결하는 요청 추적 ID")
        String traceId,
        @Schema(description = "필드 검증 등 세부 오류 목록")
        List<String> details
) {
}
