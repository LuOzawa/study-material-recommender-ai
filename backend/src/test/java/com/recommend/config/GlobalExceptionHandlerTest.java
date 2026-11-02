package com.recommend.config;

import com.recommend.client.AiServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void responseStatusExceptionKeepsItsHttpStatus() throws Exception {
        mockMvc.perform(get("/probe/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("内部令牌无效"));
    }

    @Test
    void aiServiceFailureMapsTo503() throws Exception {
        mockMvc.perform(get("/probe/ai-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/unauthorized")
        void unauthorized() {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部令牌无效");
        }

        @GetMapping("/probe/ai-down")
        void aiDown() {
            throw new AiServiceException("AI 服务不可达");
        }
    }
}
