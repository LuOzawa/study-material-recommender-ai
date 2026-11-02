package com.recommend.integration;

import com.recommend.repository.UserRepository;
import com.recommend.security.JwtUtil;
import com.recommend.service.UserPreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration checks that need a real PostgreSQL schema + seed.
 *
 * Skipped unless SPRING_DATASOURCE_URL is set (CI / local migration verification).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
@TestPropertySource(properties = "ai.service.internal-token=test-token")
class PostgresIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPreferenceService preferenceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void usernameLookupIsCaseInsensitive() {
        assertThat(userRepository.findByUsernameIgnoreCase("ADMIN")).isPresent();
        assertThat(userRepository.findByUsernameIgnoreCase("admin").orElseThrow().getUsername())
                .isEqualTo("admin");
    }

    @Test
    @Transactional
    void preferenceUpsertKeepsSingleRowAndBooleanFlag() {
        preferenceService.updatePreferenceView(2L, Map.of("autoRefresh", true, "topN", 7));
        preferenceService.updatePreferenceView(2L, Map.of("autoRefresh", true, "topN", 7));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_preferences WHERE user_id = 2", Integer.class);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT auto_refresh, top_n FROM user_preferences WHERE user_id = 2");

        assertThat(rows).isEqualTo(1);
        assertThat(row.get("auto_refresh")).isEqualTo(true);
        assertThat(((Number) row.get("top_n")).intValue()).isEqualTo(7);
    }

    @Test
    void adminEndpointRejectsRegularUser() throws Exception {
        UserDetails details = User.withUsername("zhangsan")
                .password("unused")
                .authorities("ROLE_USER")
                .build();
        String token = jwtUtil.generateToken(details);

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/internal/ai/materials"))
                .andExpect(status().isUnauthorized());
    }
}
