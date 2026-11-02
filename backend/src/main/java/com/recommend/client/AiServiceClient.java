package com.recommend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recommend.config.AiServiceProperties;
import com.recommend.dto.AiDtos;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the FastAPI AI service.  Every call carries the shared
 * internal token; failures surface as {@link AiServiceException} so the global
 * handler can answer 503 with a readable message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final AiServiceProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate restTemplate;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        RestTemplate template = new RestTemplate(factory);
        template.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        template.getMessageConverters().add(0, converter);
        this.restTemplate = template;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()
                && properties.getInternalToken() != null && !properties.getInternalToken().isBlank();
    }

    // ------------------------------------------------------------------
    // Agent endpoints
    // ------------------------------------------------------------------
    public AiDtos.ChatResult chat(long userId, String question, List<Map<String, String>> history) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("question", question);
        body.put("history", history);
        return post("/internal/v1/agent/chat", body, new ParameterizedTypeReference<>() {
        });
    }

    public AiDtos.SummaryResult summarize(long userId, long materialId, AiDtos.MaterialPayload material) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("material_id", materialId);
        body.put("material", material);
        return post("/internal/v1/agent/summarize", body, new ParameterizedTypeReference<>() {
        });
    }

    public AiDtos.SuggestionResult suggest(long userId, String targetSchool, String major,
                                           String studyPhase, List<String> favoriteTitles) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("target_school", targetSchool);
        body.put("major", major);
        body.put("study_phase", studyPhase);
        body.put("favorite_titles", favoriteTitles);
        return post("/internal/v1/agent/suggest", body, new ParameterizedTypeReference<>() {
        });
    }

    public AiDtos.SearchResult search(String query, int topK) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("top_k", topK);
        return get("/internal/v1/search", params, new ParameterizedTypeReference<>() {
        });
    }

    // ------------------------------------------------------------------
    // Index maintenance
    // ------------------------------------------------------------------
    public AiDtos.IndexResult indexMaterial(AiDtos.MaterialPayload material) {
        return post("/internal/v1/index/materials", material, new ParameterizedTypeReference<>() {
        });
    }

    public void deleteMaterial(long materialId) {
        ensureConfigured();
        try {
            restTemplate.exchange(url("/internal/v1/index/materials/{id}"), HttpMethod.DELETE,
                    new HttpEntity<>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {
                    }, materialId);
        } catch (RestClientResponseException e) {
            throw new AiServiceException(extractMessage(e), e);
        } catch (ResourceAccessException e) {
            throw new AiServiceException("AI 服务不可达：" + e.getMessage(), e);
        }
    }

    public AiDtos.RebuildResult rebuild() {
        return post("/internal/v1/index/rebuild", Map.of(), new ParameterizedTypeReference<>() {
        });
    }

    public AiDtos.IndexStats stats() {
        return get("/internal/v1/index/stats", Map.of(), new ParameterizedTypeReference<>() {
        });
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------
    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TOKEN_HEADER, properties.getInternalToken());
        return headers;
    }

    private String url(String path) {
        String base = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        return base + path;
    }

    private <T> T post(String path, Object payload, ParameterizedTypeReference<T> type) {
        ensureConfigured();
        try {
            ResponseEntity<T> response = restTemplate.exchange(
                    url(path), HttpMethod.POST, new HttpEntity<>(payload, headers()), type);
            return requireBody(response);
        } catch (RestClientResponseException e) {
            throw new AiServiceException(extractMessage(e), e);
        } catch (ResourceAccessException e) {
            throw new AiServiceException("AI 服务不可达：" + e.getMessage(), e);
        }
    }

    private <T> T get(String path, Map<String, Object> params, ParameterizedTypeReference<T> type) {
        ensureConfigured();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url(path));
        params.forEach((key, value) -> {
            if (value != null) {
                builder.queryParam(key, value);
            }
        });
        try {
            ResponseEntity<T> response = restTemplate.exchange(
                    builder.build().encode().toUri(), HttpMethod.GET,
                    new HttpEntity<>(headers()), type);
            return requireBody(response);
        } catch (RestClientResponseException e) {
            throw new AiServiceException(extractMessage(e), e);
        } catch (ResourceAccessException e) {
            throw new AiServiceException("AI 服务不可达：" + e.getMessage(), e);
        }
    }

    private <T> T requireBody(ResponseEntity<T> response) {
        T body = response.getBody();
        if (body == null) {
            throw new AiServiceException("AI 服务返回空响应");
        }
        return body;
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new AiServiceException("AI 服务未启用：请配置 AI_SERVICE_BASE_URL 与 AI_INTERNAL_TOKEN。");
        }
    }

    private String extractMessage(RestClientResponseException e) {
        String raw = e.getResponseBodyAsString();
        try {
            JsonNode node = objectMapper.readTree(raw);
            JsonNode detail = node.path("detail");
            if (detail.isObject() && detail.hasNonNull("message")) {
                return detail.get("message").asText();
            }
            if (detail.isTextual()) {
                return detail.asText();
            }
            JsonNode message = node.path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // fall through to the raw body
        }
        log.warn("AI service returned HTTP {}: {}", e.getStatusCode().value(), raw);
        return "AI 服务调用失败（HTTP " + e.getStatusCode().value() + "）";
    }
}
