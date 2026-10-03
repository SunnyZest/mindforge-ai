package com.zest.mindforgeai.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Knife4j 配置。
 * <p>
 * 注册 JWT Bearer 安全方案后，Knife4j / Swagger UI 右上角会出现 <b>Authorize</b> 按钮：
 * 先调 {@code /api/app/auth/login} 拿到 accessToken，粘进去，
 * 之后所有需要认证的接口都能在页面上直接调试。
 * <p>
 * 公开接口（登录 / 注册）用 {@code @SecurityRequirements}（空）单独摘掉锁标记。
 *
 * @author crx
 * @since 2026-10-03
 */
@Configuration
public class OpenApiConfig {

    /** 安全方案名称，需与 SecurityRequirement 中的名字一致 */
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI mindforgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MindForge AI API")
                        .version("v1")
                        .description("""
                                双前端单后端 · JWT 认证 + RBAC 授权。

                                联调步骤：
                                1. 调 `POST /api/app/auth/login` 获取 accessToken
                                2. 点右上角 **Authorize**，填入 token（只填 token 本身，不用加 "Bearer "）
                                3. 之后即可直接调试受保护接口

                                路径前缀 `/api` 由 `server.servlet.context-path` 提供，Controller 中不再重复书写。
                                """))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("格式：Authorization: Bearer <accessToken>")))
                // 全局默认需要认证；公开接口用 @SecurityRequirements（空）单独摘除
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
