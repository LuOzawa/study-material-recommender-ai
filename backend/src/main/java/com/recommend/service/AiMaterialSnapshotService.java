package com.recommend.service;

import com.recommend.dto.AiDtos;
import com.recommend.entity.Category;
import com.recommend.entity.Material;
import com.recommend.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** Maps JPA materials to the payload understood by the AI service. */
@Service
@RequiredArgsConstructor
public class AiMaterialSnapshotService {

    private final CategoryRepository categoryRepository;

    public AiDtos.MaterialPayload snapshot(Material material) {
        String categoryName = null;
        if (material.getCategoryId() != null) {
            categoryName = categoryRepository.findById(material.getCategoryId())
                    .map(Category::getName)
                    .orElse(null);
        }
        return snapshot(material, categoryName);
    }

    public AiDtos.MaterialPayload snapshot(Material material, String categoryName) {
        String status = material.getStatus() == null ? null : material.getStatus().name();
        String updatedAt = material.getUpdatedAt() == null
                ? null
                : material.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Double ratingAvg = material.getRatingAvg() == null ? null : material.getRatingAvg().doubleValue();
        return new AiDtos.MaterialPayload(
                material.getId(),
                material.getTitle(),
                material.getDescription(),
                material.getTags(),
                material.getCategoryId(),
                categoryName,
                status,
                material.getAuthor(),
                ratingAvg,
                updatedAt);
    }

    public Map<Long, String> categoryNames() {
        Map<Long, String> names = new HashMap<>();
        for (Category category : categoryRepository.findAll()) {
            names.put(category.getId(), category.getName());
        }
        return names;
    }
}
