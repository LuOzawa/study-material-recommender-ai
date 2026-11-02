package com.recommend.service;

import com.recommend.entity.Material;
import com.recommend.entity.RecommendationFeedback;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.RecommendationFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐反馈服务（点赞 / 点踩）。
 *
 * <p>这是补齐「用户反馈闭环」的核心：此前 {@code recommendation_feedback} 表、
 * {@link RecommendationFeedback} 实体和 Repository 都已建好，但<b>全项目没有任何一处写入</b>，
 * 也没有任何接口，推荐结果因此完全不受用户反馈影响，闭环是断的。</p>
 *
 * <p>反馈的作用点有两个：
 * <ol>
 *   <li>在线重排：{@link RecommendationService#applyPreference} 对点过「有用」的资料加分、
 *       对点过「没用」的资料降权并优先剔除</li>
 *   <li>管理端报表：{@code AnalyticsService.getRecommendationFeedbackDistribution()}
 *       读的就是这张表，此前因为表恒为空，饼图永远空白</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final RecommendationFeedbackRepository feedbackRepository;
    private final MaterialRepository materialRepository;

    /**
     * 提交反馈。同一用户对同一资料只保留一条记录：
     * 已存在则更新（用于「点踩」改成「有用」这类切换），不存在则新建。
     */
    @Transactional
    public RecommendationFeedback.FeedbackType submit(Long userId,
                                                      Long materialId,
                                                      RecommendationFeedback.FeedbackType type) {
        if (userId == null) {
            throw new IllegalArgumentException("用户未登录");
        }
        if (type == null) {
            throw new IllegalArgumentException("反馈类型不能为空");
        }
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("资料不存在"));
        if (material.getStatus() != Material.Status.ACTIVE) {
            throw new IllegalArgumentException("资料不存在或已下架");
        }

        RecommendationFeedback feedback = feedbackRepository
                .findByUserIdAndMaterialId(userId, materialId)
                .orElseGet(RecommendationFeedback::new);
        feedback.setUserId(userId);
        feedback.setMaterialId(materialId);
        feedback.setFeedback(type);
        feedbackRepository.save(feedback);

        log.info("用户 {} 对资料 {} 提交反馈: {}", userId, materialId, type);
        return type;
    }

    /** 撤销反馈 */
    @Transactional
    public void cancel(Long userId, Long materialId) {
        feedbackRepository.deleteByUserIdAndMaterialId(userId, materialId);
    }

    /** 某个用户的全部反馈，key = materialId */
    public Map<Long, RecommendationFeedback.FeedbackType> mapByUser(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        return feedbackRepository.findByUserId(userId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        RecommendationFeedback::getMaterialId,
                        RecommendationFeedback::getFeedback,
                        (a, b) -> b));
    }

    /** 当前用户对某个资料的反馈，没有则返回 null */
    public RecommendationFeedback.FeedbackType get(Long userId, Long materialId) {
        if (userId == null) {
            return null;
        }
        return feedbackRepository.findByUserIdAndMaterialId(userId, materialId)
                .map(RecommendationFeedback::getFeedback)
                .orElse(null);
    }

    /** 汇总统计，供接口返回与管理端展示 */
    public Map<String, Object> summary(Long userId) {
        Map<Long, RecommendationFeedback.FeedbackType> mine = mapByUser(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("likeCount", feedbackRepository.countByFeedback(RecommendationFeedback.FeedbackType.LIKE));
        result.put("dislikeCount", feedbackRepository.countByFeedback(RecommendationFeedback.FeedbackType.DISLIKE));
        result.put("mine", mine.entrySet().stream()
                .collect(HashMap::new, (m, e) -> m.put(String.valueOf(e.getKey()), e.getValue().name()), HashMap::putAll));
        return result;
    }

    /** 供 AnalyticsService 复用（避免重复注入仓库） */
    public List<RecommendationFeedback> listAll() {
        return feedbackRepository.findAll();
    }
}
