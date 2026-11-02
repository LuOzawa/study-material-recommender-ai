package com.recommend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection settings for the FastAPI AI service (LangGraph + vLLM + pgvector).
 * All values come from environment variables; the internal token must match
 * {@code AI_INTERNAL_TOKEN} in the AI service.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.service")
public class AiServiceProperties {

    private boolean enabled = true;

    private String baseUrl = "http://localhost:8090";

    private String internalToken = "";

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 60000;
}
