package com.recommend.service;

import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserBehaviorRepository;
import com.recommend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock private UserBehaviorRepository behaviorRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private UserRepository userRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AnalyticsService analyticsService;

    @Test
    void cleanBehaviorLogsReportsBeforeRemovedAfter() {
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_behaviors", Number.class))
                .thenReturn(10, 8);
        when(jdbcTemplate.update(anyString())).thenReturn(2);

        Map<String, Object> result = analyticsService.cleanBehaviorLogs();

        assertThat(result.get("before")).isEqualTo(10L);
        assertThat(result.get("removed")).isEqualTo(2);
        assertThat(result.get("after")).isEqualTo(8L);
    }
}
