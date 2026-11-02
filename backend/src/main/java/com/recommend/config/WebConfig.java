package com.recommend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置。
 *
 * <p>原实现为 {@code allowedOriginPatterns("*") + allowCredentials(true)}，
 * 等于把任意站点都加入白名单并允许其携带凭据。这里改为按环境变量白名单，
 * 默认只放开本地开发端口。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许的前端来源，逗号分隔。生产环境务必显式配置，例如：
     * {@code CORS_ALLOWED_ORIGINS=https://your-domain.com}
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
