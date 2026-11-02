package com.recommend.client;

import com.recommend.config.AiServiceProperties;
import com.recommend.dto.AiDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

class AiServiceClientTest {

    private AiServiceProperties properties;
    private AiServiceClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new AiServiceProperties();
        properties.setBaseUrl("http://ai.test");
        properties.setInternalToken("secret-token");
        client = new AiServiceClient(properties);
        client.init();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void chatSendsTokenAndHistoryAndParsesCitations() {
        server.expect(requestTo("http://ai.test/internal/v1/agent/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"user_id":7,"question":"数学一重点？",
                         "history":[{"question":"上一问","answer":"上一答"}]}
                        """, false))
                .andRespond(withSuccess("""
                        {"answer":"极限与导数[1]","citations":[{"index":1,"material_id":3,
                        "title":"数学一提纲","chunk_index":0,"score":0.91,"snippet":"极限"}],
                        "references":[],"route":"materials","query":"数学一 复习资料",
                        "rewrites":0,"model":"qwen2.5-7b-instruct","latency_ms":123}
                        """, MediaType.APPLICATION_JSON));

        AiDtos.ChatResult result = client.chat(7, "数学一重点？",
                List.of(Map.of("question", "上一问", "answer", "上一答")));

        assertThat(result.answer()).isEqualTo("极限与导数[1]");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).materialId()).isEqualTo(3L);
        assertThat(result.latencyMs()).isEqualTo(123L);
        assertThat(result.route()).isEqualTo("materials");
        server.verify();
    }

    @Test
    void upstreamErrorBecomesReadableException() {
        server.expect(requestTo("http://ai.test/internal/v1/agent/chat"))
                .andRespond(withStatus(SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"detail":{"code":"LLM_UNAVAILABLE","message":"vLLM 不可达：connection refused"}}
                                """));

        assertThatThrownBy(() -> client.chat(1, "q", List.of()))
                .isInstanceOf(AiServiceException.class)
                .hasMessage("vLLM 不可达：connection refused");
    }

    @Test
    void missingConfigurationFailsFastWithoutHttp() {
        properties.setInternalToken("");
        assertThatThrownBy(() -> client.chat(1, "q", List.of()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("AI 服务未启用");
    }

    @Test
    void searchSendsQueryParameters() {
        server.expect(ExpectedCount.once(), request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/internal/v1/search");
                    assertThat(request.getURI().getQuery()).contains("top_k=3");
                    assertThat(request.getURI().getQuery()).contains("q=");
                })
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"query":"数学","matches":[{"material_id":5,"title":"数一","chunk_index":0,
                        "score":0.8,"snippet":"s","tags":"数学"}]}
                        """, MediaType.APPLICATION_JSON));

        AiDtos.SearchResult result = client.search("数学", 3);

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).materialId()).isEqualTo(5L);
        server.verify();
    }

    @Test
    void rebuildUsesConfiguredTimeoutAndToken() {
        server.expect(requestTo("http://ai.test/internal/v1/index/rebuild"))
                .andRespond(withSuccess("""
                        {"indexed":2,"skipped":1,"removed":0,"failed":[],"chunks":5,"duration_ms":42}
                        """, MediaType.APPLICATION_JSON));

        AiDtos.RebuildResult result = client.rebuild();

        assertThat(result.indexed()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.durationMs()).isEqualTo(42);
        server.verify();
    }
}
