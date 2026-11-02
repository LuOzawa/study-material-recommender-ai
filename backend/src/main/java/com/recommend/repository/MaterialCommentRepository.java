package com.recommend.repository;

import com.recommend.entity.MaterialComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialCommentRepository extends JpaRepository<MaterialComment, Long> {

    List<MaterialComment> findByMaterialIdOrderByCreatedAtDesc(Long materialId);
}

