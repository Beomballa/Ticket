package com.portfolio.fanevent.support.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "StagePass API",
        version = "v1",
        description = "공연·팬 이벤트의 한정 재고 예약과 운영을 위한 REST API"
))
@SecurityScheme(
        name = OpenApiConfiguration.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "로그인 응답의 Access Token을 입력합니다."
)
public class OpenApiConfiguration {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI stagePassOpenApi() {
        Components components = new Components()
                .schemas(ModelConverters.getInstance().read(ApiError.class))
                .addResponses("ApiError", apiErrorResponse(
                        "표준 API 오류 응답",
                        example("기본 오류", "INVALID_REQUEST", "요청을 처리할 수 없습니다.")))
                .addResponses("BadRequest", apiErrorResponse(
                        "요청 형식 또는 검증 오류",
                        example(
                                "필드 검증 실패",
                                "VALIDATION_ERROR",
                                "요청값이 올바르지 않습니다.",
                                List.of("items[0].quantity: 1 이상이어야 합니다.")),
                        example("잘못된 요청", "INVALID_REQUEST", "조회 기간이 올바르지 않습니다.")))
                .addResponses("Unauthorized", apiErrorResponse(
                        "인증 필요 또는 로그인 실패",
                        example("인증 필요", "AUTHENTICATION_REQUIRED", "유효한 인증이 필요합니다."),
                        example("로그인 실패", "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.")))
                .addResponses("Forbidden", apiErrorResponse(
                        "권한 부족",
                        example("관리자 권한 필요", "ACCESS_DENIED", "요청한 작업을 수행할 권한이 없습니다.")))
                .addResponses("NotFound", apiErrorResponse(
                        "리소스를 찾을 수 없음",
                        example("리소스 없음", "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.")))
                .addResponses("Conflict", apiErrorResponse(
                        "상태·재고·멱등성 충돌",
                        example("재고 부족", "INSUFFICIENT_STOCK", "예약 가능한 재고가 부족합니다."),
                        example("상태 전이 거부", "INVALID_STATE_TRANSITION", "현재 상태에서는 요청을 처리할 수 없습니다."),
                        example("멱등키 재사용", "IDEMPOTENCY_KEY_REUSED", "같은 멱등키가 다른 요청에 사용되었습니다."),
                        example("결제 결과 확인 중", "PAYMENT_RESULT_UNKNOWN", "결제 승인 결과를 확인 중입니다."),
                        example("동시 재고 변경", "INVENTORY_CONFLICT", "재고를 확인한 뒤 다시 시도해 주세요.")))
                .addResponses("UnprocessableEntity", apiErrorResponse(
                        "결제 승인 거절",
                        example("결제 거절", "PAYMENT_DECLINED", "모의 결제가 승인되지 않았습니다.")))
                .addResponses("TooManyRequests", apiErrorResponse(
                        "예약 요청 속도 제한 초과",
                        example("요청 제한", "RESERVATION_RATE_LIMITED", "잠시 후 다시 시도해 주세요."))
                        .addHeaderObject("Retry-After", new Header()
                                .description("다시 요청할 수 있을 때까지의 초")
                                .schema(new IntegerSchema().format("int64"))
                                .example(60)));
        return new OpenAPI().components(components);
    }

    @Bean
    OpenApiCustomizer commonErrorResponseCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((method, operation) -> {
                    addResponse(operation.getResponses(), "default", "ApiError");
                    addResponse(operation.getResponses(), "400", "BadRequest");

                    boolean protectedOperation = operation.getSecurity() != null
                            && !operation.getSecurity().isEmpty();
                    if (protectedOperation || "login".equals(operation.getOperationId())) {
                        addResponse(operation.getResponses(), "401", "Unauthorized");
                    }
                    if (path.startsWith("/api/admin/")) {
                        addResponse(operation.getResponses(), "403", "Forbidden");
                    }
                    if (path.contains("{") || (path.startsWith("/api/admin/") && !"GET".equals(method.name()))) {
                        addResponse(operation.getResponses(), "404", "NotFound");
                    }

                    switch (operation.getOperationId()) {
                        case "signup", "hold", "confirm", "cancel", "retry" ->
                                addResponse(operation.getResponses(), "409", "Conflict");
                        default -> {
                            if (path.startsWith("/api/admin/") && !"GET".equals(method.name())) {
                                addResponse(operation.getResponses(), "409", "Conflict");
                            }
                        }
                    }
                    if ("confirm".equals(operation.getOperationId())) {
                        addResponse(operation.getResponses(), "422", "UnprocessableEntity");
                    }
                    if ("hold".equals(operation.getOperationId())) {
                        addResponse(operation.getResponses(), "429", "TooManyRequests");
                    }
                }));
    }

    private static void addResponse(
            io.swagger.v3.oas.models.responses.ApiResponses responses,
            String status,
            String component
    ) {
        responses.addApiResponse(status, new ApiResponse().$ref("#/components/responses/" + component));
    }

    private static ApiResponse apiErrorResponse(String description, ErrorExample... examples) {
        MediaType mediaType = new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/ApiError"));
        for (ErrorExample errorExample : examples) {
            mediaType.addExamples(
                    errorExample.body().code(),
                    new Example().summary(errorExample.summary()).value(errorExample.body()));
        }
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json", mediaType));
    }

    private static ErrorExample example(String summary, String code, String message) {
        return example(summary, code, message, List.of());
    }

    private static ErrorExample example(String summary, String code, String message, List<String> details) {
        return new ErrorExample(summary, new ApiError(code, message, "01HZY7Y3V8Q2XK6P4M9N1C5B0A", details));
    }

    private record ErrorExample(String summary, ApiError body) {
    }

    @Bean
    GroupedOpenApi publicApi(OpenApiCustomizer commonErrorResponseCustomizer) {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/api/auth/**", "/api/events/**")
                .addOpenApiCustomizer(commonErrorResponseCustomizer)
                .build();
    }

    @Bean
    GroupedOpenApi memberApi(OpenApiCustomizer commonErrorResponseCustomizer) {
        return GroupedOpenApi.builder()
                .group("member")
                .pathsToMatch("/api/members/**", "/api/reservations/**")
                .addOpenApiCustomizer(commonErrorResponseCustomizer)
                .build();
    }

    @Bean
    GroupedOpenApi adminApi(OpenApiCustomizer commonErrorResponseCustomizer) {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/api/admin/**")
                .addOpenApiCustomizer(commonErrorResponseCustomizer)
                .build();
    }
}
