package com.recommend.controller;

import com.recommend.dto.ApiResponse;
import com.recommend.entity.Material;
import com.recommend.entity.RecommendationFeedback;
import com.recommend.repository.UserRepository;
import com.recommend.service.FeedbackService;
import com.recommend.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final FeedbackService feedbackService;
    private final UserRepository userRepository;

    @GetMapping("/public")
    public ApiResponse<List<Material>> getPublicRecommendations() {
        return ApiResponse.success(recommendationService.getGuestRecommendations());
    }

    @GetMapping
    public ApiResponse<List<Material>> getRecommendations(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        return ApiResponse.success(recommendationService.getRecommendations(userId));
    }

    @PostMapping("/refresh")
    public ApiResponse<Void> refresh(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        recommendationService.refreshForUser(userId);
        return ApiResponse.success();
    }

    // ---------------------------------------------------------------------
    // 推荐反馈（补上的闭环）
    // ---------------------------------------------------------------------

    /**
     * 对某条推荐资料提交「有用 / 没用」。
     *
     * <p>请求体：{@code {"feedback": "LIKE"}} 或 {@code {"feedback": "DISLIKE"}}。
     * 同一用户对同一资料只保留一条记录，重复提交视为切换。</p>
     */
    @PostMapping("/{materialId}/feedback")
    public ApiResponse<Map<String, Object>> submitFeedback(
            @PathVariable Long materialId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {

        String raw = body == null ? null : body.get("feedback");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("请提供 feedback（LIKE 或 DISLIKE）");
        }
        RecommendationFeedback.FeedbackType type;
        try {
            type = RecommendationFeedback.FeedbackType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("feedback 只能是 LIKE 或 DISLIKE");
        }

        Long userId = currentUserId(userDetails);
        RecommendationFeedback.FeedbackType saved = feedbackService.submit(userId, materialId, type);
        return ApiResponse.success(Map.of(
                "materialId", materialId,
                "feedback", saved.name()
        ));
    }

    /** 撤销对某条资料的反馈 */
    @DeleteMapping("/{materialId}/feedback")
    public ApiResponse<Void> cancelFeedback(@PathVariable Long materialId,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        feedbackService.cancel(currentUserId(userDetails), materialId);
        return ApiResponse.success();
    }

    /** 当前用户的全部反馈（前端用来高亮「有用/没用」按钮） */
    @GetMapping("/feedback")
    public ApiResponse<Map<String, Object>> myFeedback(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        return ApiResponse.success(feedbackService.summary(userId));
    }

    private Long currentUserId(UserDetails userDetails) {
        return userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"))
                .getId();
    }
}
