package com.recommend.repository;

import com.recommend.entity.UserRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRatingRepository extends JpaRepository<UserRating, Long> {

    Optional<UserRating> findByUserIdAndMaterialId(Long userId, Long materialId);

    List<UserRating> findByUserIdOrderByUpdatedAtDesc(Long userId);

    @Query("SELECT AVG(r.rating) FROM UserRating r WHERE r.materialId = :materialId")
    Double findAvgRatingByMaterialId(@Param("materialId") Long materialId);

    @Query("SELECT COUNT(r) FROM UserRating r WHERE r.materialId = :materialId")
    Long countByMaterialId(@Param("materialId") Long materialId);

    void deleteByUserIdAndMaterialId(Long userId, Long materialId);
}
