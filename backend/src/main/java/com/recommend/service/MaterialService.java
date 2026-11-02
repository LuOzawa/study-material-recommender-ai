package com.recommend.service;

import com.recommend.dto.MaterialDTO;
import com.recommend.entity.Material;
import com.recommend.entity.MaterialComment;
import com.recommend.entity.MaterialLike;
import com.recommend.entity.UserBehavior;
import com.recommend.entity.User;
import com.recommend.entity.UserRating;
import com.recommend.event.MaterialIndexEvent;
import com.recommend.repository.MaterialCommentRepository;
import com.recommend.repository.MaterialLikeRepository;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.CategoryRepository;
import com.recommend.repository.UserFavoriteRepository;
import com.recommend.repository.UserBehaviorRepository;
import com.recommend.repository.UserRatingRepository;
import com.recommend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final CategoryRepository categoryRepository;
    private final UserBehaviorRepository behaviorRepository;
    private final UserRatingRepository ratingRepository;
    private final UserFavoriteRepository favoriteRepository;
    private final MaterialLikeRepository likeRepository;
    private final MaterialCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final HdfsService hdfsService;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Page<Material> list(String keyword, Long categoryId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        if (keyword != null && !keyword.isBlank()) {
            return materialRepository.searchActive(keyword, pageable);
        }
        if (categoryId != null) {
            List<Long> categoryIds = resolveCategoryIds(categoryId);
            return materialRepository.findByStatusAndCategoryIdIn(Material.Status.ACTIVE, categoryIds, pageable);
        }
        return materialRepository.findByStatus(Material.Status.ACTIVE, pageable);
    }

    private List<Long> resolveCategoryIds(Long categoryId) {
        List<com.recommend.entity.Category> categories = categoryRepository.findAll();
        Map<Long, List<Long>> childrenMap = new HashMap<>();
        for (com.recommend.entity.Category category : categories) {
            if (category.getParentId() == null || category.getId() == null) {
                continue;
            }
            childrenMap.computeIfAbsent(category.getParentId(), key -> new ArrayList<>())
                    .add(category.getId());
        }

        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current == null || !ids.add(current)) {
                continue;
            }
            for (Long childId : childrenMap.getOrDefault(current, List.of())) {
                if (!ids.contains(childId)) {
                    queue.add(childId);
                }
            }
        }

        return new ArrayList<>(ids);
    }

    @Transactional
    public Material getDetail(Long id, Long userId) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("资料不存在"));

        // 原实现对状态不做任何判断，未审核（PENDING）与已删除（DELETED）的资料
        // 只要知道 id 就能从公开接口读到详情。此处限制为仅 ACTIVE 可见。
        if (material.getStatus() != Material.Status.ACTIVE) {
            throw new IllegalArgumentException("资料不存在或已下架");
        }

        if (userId != null) {
            UserBehavior behavior = new UserBehavior();
            behavior.setUserId(userId);
            behavior.setMaterialId(id);
            behavior.setAction(UserBehavior.Action.VIEW);
            behaviorRepository.save(behavior);
            materialRepository.incrementViewCount(id);
        }
        return material;
    }

    @Transactional
    public Material create(MaterialDTO dto, Long uploaderId, MultipartFile file) throws IOException {
        Material material = new Material();
        material.setTitle(dto.getTitle());
        material.setDescription(dto.getDescription());
        material.setCategoryId(dto.getCategoryId());
        material.setTags(dto.getTags());
        material.setAuthor(dto.getAuthor());
        material.setUploaderId(uploaderId);
        // New uploads enter moderation queue first.
        material.setStatus(Material.Status.PENDING);
        material = materialRepository.save(material);

        if (file != null && !file.isEmpty()) {
            String hdfsPath = hdfsService.uploadFile(material.getId(), file.getOriginalFilename(), file.getInputStream());
            material.setHdfsPath(hdfsPath);
            material.setFileName(file.getOriginalFilename());
            material.setFileSize(file.getSize());
            material = materialRepository.save(material);
        }

        publisher.publishEvent(new MaterialIndexEvent(material.getId()));
        return material;
    }

    @Transactional
    public Material update(Long id, MaterialDTO dto) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("资料不存在"));
        if (dto.getTitle() != null) material.setTitle(dto.getTitle());
        if (dto.getDescription() != null) material.setDescription(dto.getDescription());
        if (dto.getCategoryId() != null) material.setCategoryId(dto.getCategoryId());
        if (dto.getTags() != null) material.setTags(dto.getTags());
        if (dto.getAuthor() != null) material.setAuthor(dto.getAuthor());
        Material saved = materialRepository.save(material);
        publisher.publishEvent(new MaterialIndexEvent(saved.getId()));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("资料不存在"));
        material.setStatus(Material.Status.DELETED);
        materialRepository.save(material);
        publisher.publishEvent(new MaterialIndexEvent(id));
    }

    @Transactional
    public Map<String, Object> rateMaterial(Long userId, Long materialId, int rating) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("评分须在1-5之间");

        UserRating userRating = ratingRepository.findByUserIdAndMaterialId(userId, materialId)
                .orElseGet(UserRating::new);
        userRating.setUserId(userId);
        userRating.setMaterialId(materialId);
        userRating.setRating((byte) rating);
        ratingRepository.save(userRating);

        // Record behavior
        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setMaterialId(materialId);
        behavior.setAction(UserBehavior.Action.RATE);
        behavior.setScore(BigDecimal.valueOf(rating));
        behaviorRepository.save(behavior);

        // Update material avg rating
        Double avg = ratingRepository.findAvgRatingByMaterialId(materialId);
        Long count = ratingRepository.countByMaterialId(materialId);
        Material material = materialRepository.findById(materialId).orElseThrow();
        material.setRatingAvg(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP));
        material.setRatingCount(count.intValue());
        materialRepository.save(material);

        return Map.of("ratingAvg", avg, "ratingCount", count);
    }

    @Transactional
    public DownloadPayload prepareDownload(Long userId, Long materialId) throws IOException {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material not found"));

        if (material.getStatus() != Material.Status.ACTIVE) {
            throw new IllegalArgumentException("Material is not available for download");
        }
        if (material.getHdfsPath() == null || material.getHdfsPath().isBlank()) {
            throw new IllegalArgumentException("Material file path is missing");
        }

        if (userId != null) {
            UserBehavior behavior = new UserBehavior();
            behavior.setUserId(userId);
            behavior.setMaterialId(materialId);
            behavior.setAction(UserBehavior.Action.DOWNLOAD);
            behaviorRepository.save(behavior);
            materialRepository.incrementDownloadCount(materialId);
        }

        InputStream stream = hdfsService.downloadFile(material.getHdfsPath());
        String fileName = Optional.ofNullable(material.getFileName())
                .filter(s -> !s.isBlank())
                .orElse("material-" + materialId);
        long fileSize = Optional.ofNullable(material.getFileSize()).orElse(-1L);
        return new DownloadPayload(stream, fileName, fileSize);
    }

    public Map<String, Object> getLikeState(Long userId, Long materialId) {
        ensureMaterialExists(materialId);
        long likeCount = likeRepository.countByMaterialId(materialId);
        boolean liked = userId != null && likeRepository.existsByUserIdAndMaterialId(userId, materialId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("liked", liked);
        result.put("likeCount", likeCount);
        return result;
    }

    @Transactional
    public Map<String, Object> toggleLike(Long userId, Long materialId) {
        ensureMaterialExists(materialId);
        boolean exists = likeRepository.existsByUserIdAndMaterialId(userId, materialId);
        if (exists) {
            likeRepository.deleteByUserIdAndMaterialId(userId, materialId);
        } else {
            MaterialLike like = new MaterialLike();
            like.setUserId(userId);
            like.setMaterialId(materialId);
            likeRepository.save(like);

            UserBehavior behavior = new UserBehavior();
            behavior.setUserId(userId);
            behavior.setMaterialId(materialId);
            behavior.setAction(UserBehavior.Action.LIKE);
            behaviorRepository.save(behavior);
        }
        return getLikeState(userId, materialId);
    }

    public boolean getFavoriteStatus(Long userId, Long materialId) {
        if (userId == null) {
            return false;
        }
        ensureMaterialExists(materialId);
        return favoriteRepository.existsByUserIdAndMaterialId(userId, materialId);
    }

    public List<Map<String, Object>> getComments(Long materialId) {
        ensureMaterialExists(materialId);
        List<MaterialComment> comments = commentRepository.findByMaterialIdOrderByCreatedAtDesc(materialId);
        if (comments.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = comments.stream().map(MaterialComment::getUserId).collect(Collectors.toSet());
        Map<Long, String> userNameMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));

        return comments.stream().map(comment -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", comment.getId());
            row.put("userId", comment.getUserId());
            row.put("username", userNameMap.getOrDefault(comment.getUserId(), "未知用户"));
            row.put("content", comment.getContent());
            row.put("createdAt", comment.getCreatedAt() != null ? comment.getCreatedAt().format(DATETIME_FORMATTER) : null);
            return row;
        }).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> addComment(Long userId, Long materialId, String content) {
        ensureMaterialExists(materialId);
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("评论长度不能超过500字");
        }

        MaterialComment comment = new MaterialComment();
        comment.setUserId(userId);
        comment.setMaterialId(materialId);
        comment.setContent(normalized);
        comment = commentRepository.save(comment);

        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setMaterialId(materialId);
        behavior.setAction(UserBehavior.Action.COMMENT);
        behaviorRepository.save(behavior);

        String username = userRepository.findById(userId).map(User::getUsername).orElse("未知用户");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", comment.getId());
        row.put("userId", comment.getUserId());
        row.put("username", username);
        row.put("content", comment.getContent());
        row.put("createdAt", comment.getCreatedAt() != null ? comment.getCreatedAt().format(DATETIME_FORMATTER) : null);
        return row;
    }

    private void ensureMaterialExists(Long materialId) {
        materialRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("资料不存在"));
    }

    public record DownloadPayload(InputStream stream, String fileName, long fileSize) {}
}
