package com.recommend.repository;

import com.recommend.entity.MaterialLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialLikeRepository extends JpaRepository<MaterialLike, Long> {

    boolean existsByUserIdAndMaterialId(Long userId, Long materialId);

    void deleteByUserIdAndMaterialId(Long userId, Long materialId);

    long countByMaterialId(Long materialId);
}

