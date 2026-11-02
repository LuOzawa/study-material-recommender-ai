package com.recommend.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 单测：签发、校验、过期判定、密钥强度自检、篡改检测。
 *
 * 这些是鉴权链路的根，出问题是"能登录任意账号"级别的漏洞，
 * 因此不依赖 Spring 容器，直接用反射注入配置做纯单元测试。
 */
class JwtUtilTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 字节，满足 HS256
    private static final long ONE_HOUR = 3_600_000L;

    private JwtUtil jwtUtil;
    private UserDetails alice;
    private UserDetails bob;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", ONE_HOUR);

        alice = new User("alice", "irrelevant", Collections.emptyList());
        bob = new User("bob", "irrelevant", Collections.emptyList());
    }

    @Test
    @DisplayName("签发的 token 能取回原用户名")
    void generatedTokenCarriesUsername() {
        String token = jwtUtil.generateToken(alice);
        assertNotNull(token);
        assertEquals("alice", jwtUtil.extractUsername(token));
    }

    @Test
    @DisplayName("同一用户的未过期 token 校验通过")
    void tokenIsValidForMatchingUser() {
        assertTrue(jwtUtil.isTokenValid(jwtUtil.generateToken(alice), alice));
    }

    @Test
    @DisplayName("他人的 token 校验失败 —— 防止横向越权")
    void tokenIsInvalidForAnotherUser() {
        assertFalse(jwtUtil.isTokenValid(jwtUtil.generateToken(alice), bob));
    }

    @Test
    @DisplayName("过期 token 校验失败")
    void expiredTokenIsInvalid() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1_000L);
        String expired = jwtUtil.generateToken(alice);
        assertFalse(jwtUtil.isTokenValid(expired, alice));
    }

    @Test
    @DisplayName("被篡改的 token 在校验接口上返回 false（不外抛，避免调用方漏 catch 得到 500）")
    void tamperedTokenIsInvalidNotThrowing() {
        String token = jwtUtil.generateToken(alice);
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'e' ? 'f' : 'e';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];
        assertFalse(jwtUtil.isTokenValid(tampered, alice));
    }

    @Test
    @DisplayName("被篡改的 token 直接抛 JwtException，而不是被当成合法身份")
    void tamperedTokenIsRejected() {
        String token = jwtUtil.generateToken(alice);
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'e' ? 'f' : 'e';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];
        assertThrows(JwtException.class, () -> jwtUtil.extractUsername(tampered));
    }

    @Test
    @DisplayName("签名密钥不同的服务无法解析对方 token")
    void tokenFromAnotherSecretIsRejected() {
        JwtUtil other = new JwtUtil();
        ReflectionTestUtils.setField(other, "secret", "ffffffffffffffffffffffffffffffff");
        ReflectionTestUtils.setField(other, "expiration", ONE_HOUR);

        String foreignToken = other.generateToken(alice);
        assertThrows(JwtException.class, () -> jwtUtil.extractUsername(foreignToken));
    }

    @Test
    @DisplayName("密钥为空时启动即失败 —— 不允许带着默认值上线")
    void blankSecretFailsFast() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "   ");
        IllegalStateException ex = assertThrows(IllegalStateException.class, jwtUtil::validateSecret);
        assertTrue(ex.getMessage().contains("JWT_SECRET"));
    }

    @Test
    @DisplayName("密钥长度不足 32 字节时启动即失败（HS256 最小值）")
    void shortSecretFailsFast() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "too-short-secret");
        IllegalStateException ex = assertThrows(IllegalStateException.class, jwtUtil::validateSecret);
        assertTrue(ex.getMessage().contains("32"));
    }

    @Test
    @DisplayName("合规密钥通过自检")
    void validSecretPassesValidation() {
        assertDoesNotThrow(jwtUtil::validateSecret);
    }
}
