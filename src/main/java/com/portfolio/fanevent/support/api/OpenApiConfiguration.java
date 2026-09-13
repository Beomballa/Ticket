package com.portfolio.fanevent.support.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
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
                .addResponses("ApiError", new ApiResponse()
                        .description("표준 API 오류 응답")
                        .content(new Content().addMediaType(
                                "application/json",
                                new io.swagger.v3.oas.models.media.MediaType()
                                        .schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
        return new OpenAPI().components(components);
    }

    @Bean
    OpenApiCustomizer commonErrorResponseCustomizer() {
        return openApi -> openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .forEach(operation -> operation.getResponses().addApiResponse(
                        "default",
                        new ApiResponse().$ref("#/components/responses/ApiError")));
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
