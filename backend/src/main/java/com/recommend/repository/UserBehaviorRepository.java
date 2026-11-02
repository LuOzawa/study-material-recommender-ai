package com.recommend.repository;

import com.recommend.entity.UserBehavior;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserBehaviorRepository extends JpaRepository<UserBehavior, Long> {

    List<UserBehavior> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("SELECT ub.materialId, COUNT(ub) as cnt FROM UserBehavior ub WHERE ub.action = 'VIEW' GROUP BY ub.materialId ORDER BY cnt DESC")
    List<Object[]> findTopViewedMaterials(Pageable pageable);

    @Query("SELECT ub.materialId, COUNT(ub) as cnt FROM UserBehavior ub WHERE ub.action IN ('VIEW','LIKE','COLLECT','DOWNLOAD') GROUP BY ub.materialId ORDER BY cnt DESC")
    List<Object[]> findMaterialHotness(Pageable pageable);

    @Query("SELECT u.studyPhase, COUNT(u) FROM User u GROUP BY u.studyPhase")
    List<Object[]> countUsersByStudyPhase();

    long deleteByUserId(Long userId);

    long deleteByIdAndUserId(Long id, Long userId);
}
