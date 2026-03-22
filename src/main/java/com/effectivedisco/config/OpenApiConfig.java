package com.effectivedisco.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI / OpenAPI 3.0 설정.
 * /swagger-ui.html 에서 문서를 확인할 수 있다.
 * JWT Bearer 토큰 인증 스키마를 전역으로 등록한다.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Effective-Disco BBS API",
        version = "v1",
        description = "게시판 서비스 REST API 문서. /api/auth/login으로 토큰 발급 후 Authorize 버튼에 입력하세요."))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {
}
