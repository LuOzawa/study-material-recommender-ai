package com.recommend.repository;

import com.recommend.entity.Recommendation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByUserIdOrderByScoreDesc(Long userId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM Recommendation r WHERE r.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
