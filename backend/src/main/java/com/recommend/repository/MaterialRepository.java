package com.recommend.repository;

import com.recommend.entity.Material;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    Page<Material> findByStatusAndTitleContainingIgnoreCase(Material.Status status, String title, Pageable pageable);

    Page<Material> findByStatus(Material.Status status, Pageable pageable);

    Page<Material> findByStatusAndUpdatedAtGreaterThanEqual(Material.Status status,
                                                            java.time.LocalDateTime since,
                                                            Pageable pageable);

    Page<Material> findByStatusAndCategoryId(Material.Status status, Long categoryId, Pageable pageable);

    Page<Material> findByStatusAndCategoryIdIn(Material.Status status, List<Long> categoryIds, Pageable pageable);

    @Query("SELECT m FROM Material m WHERE m.status = 'ACTIVE' AND " +
           "(LOWER(m.title) LIKE LOWER(CONCAT('%',:kw,'%')) OR LOWER(m.tags) LIKE LOWER(CONCAT('%',:kw,'%')) OR LOWER(m.description) LIKE LOWER(CONCAT('%',:kw,'%')))")
    Page<Material> searchActive(@Param("kw") String keyword, Pageable pageable);

    @Query("SELECT m FROM Material m WHERE m.id IN :ids AND m.status = 'ACTIVE'")
    List<Material> findActiveByIds(@Param("ids") List<Long> ids);

    @Modifying
    @Query("UPDATE Material m SET m.viewCount = m.viewCount + 1 WHERE m.id = :id")
    void incrementViewCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Material m SET m.downloadCount = m.downloadCount + 1 WHERE m.id = :id")
    void incrementDownloadCount(@Param("id") Long id);

    @Query("SELECT m FROM Material m WHERE m.status = 'ACTIVE' ORDER BY m.viewCount DESC")
    List<Material> findTopByViewCount(Pageable pageable);
}
