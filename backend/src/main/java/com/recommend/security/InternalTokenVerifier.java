package com.recommend.security;

import com.recommend.client.AiServiceException;
import com.recommend.config.AiServiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time check of the shared secret used by the AI service when it
 * pulls material snapshots from {@code /api/internal/**}.
 */
@Component
@RequiredArgsConstructor
public class InternalTokenVerifier {

    private final AiServiceProperties properties;

    public void require(String provided) {
        String expected = properties.getInternalToken();
        if (expected == null || expected.isBlank()) {
            throw new AiServiceException("AI 内部接口未启用：未配置 ai.service.internal-token");
        }
        byte[] given = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部令牌无效");
        }
    }
}
