package com.recommend.controller;

import com.recommend.dto.AiDtos;
import com.recommend.entity.Material;
import com.recommend.repository.MaterialRepository;
import com.recommend.security.InternalTokenVerifier;
import com.recommend.service.AiMaterialSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Internal read-only API consumed by the AI service when it (re)builds the
 * vector index.  Protected by a shared token, not by user JWT.
 */
@RestController
@RequestMapping("/api/internal/ai")
@RequiredArgsConstructor
public class InternalAiController {

    private static final int MAX_PAGE_SIZE = 200;

    private final MaterialRepository materialRepository;
    private final AiMaterialSnapshotService snapshotService;
    private final InternalTokenVerifier tokenVerifier;

    @GetMapping("/materials")
    public AiDtos.MaterialPage materials(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {

        tokenVerifier.require(token);

        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.asc("updatedAt"), Sort.Order.asc("id")));

        Page<Material> result = since == null
                ? materialRepository.findByStatus(Material.Status.ACTIVE, pageable)
                : materialRepository.findByStatusAndUpdatedAtGreaterThanEqual(
                        Material.Status.ACTIVE, since, pageable);

        Map<Long, String> categoryNames = snapshotService.categoryNames();
        List<AiDtos.MaterialPayload> items = result.getContent().stream()
                .map(material -> snapshotService.snapshot(
                        material, categoryNames.get(material.getCategoryId())))
                .toList();

        return new AiDtos.MaterialPage(items, safePage, safeSize, result.hasNext(), result.getTotalElements());
    }
}
