package com.recommend.controller;

import com.recommend.dto.ApiResponse;
import com.recommend.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/hotness")
    public ApiResponse<List<Map<String, Object>>> getMaterialHotness() {
        return ApiResponse.success(analyticsService.getMaterialHotness());
    }

    @GetMapping("/user-profile")
    public ApiResponse<List<Map<String, Object>>> getUserProfile() {
        return ApiResponse.success(analyticsService.getUserStudyPhaseDistribution());
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        return ApiResponse.success(analyticsService.getSummaryStats());
    }

    /**
     * 看板数据。
     *
     * @param days 行为趋势图的时间窗口（天），默认 14。可选 7~60，
     *             演示数据较旧时（例如答辩时数据是几个月前造的）可调大，
     *             否则趋势图会是空白的。
     */
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> getDashboard(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "14") int days) {
        return ApiResponse.success(analyticsService.getDashboardData(days));
    }

    @PostMapping("/clean")
    public ApiResponse<Map<String, Object>> cleanBehaviors() {
        return ApiResponse.success(analyticsService.cleanBehaviorLogs());
    }
}
