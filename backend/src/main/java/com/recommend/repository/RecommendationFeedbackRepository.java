package com.recommend.repository;

import com.recommend.entity.RecommendationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {

    Optional<RecommendationFeedback> findByUserIdAndMaterialId(Long userId, Long materialId);

    /** 某个用户的全部反馈，用于在线重排时一次性取出（避免逐条查询） */
    List<RecommendationFeedback> findByUserId(Long userId);

    void deleteByUserIdAndMaterialId(Long userId, Long materialId);

    /** 统计用：某种反馈的总条数 */
    long countByFeedback(RecommendationFeedback.FeedbackType feedback);
}
