package com.recommend.service;

import com.recommend.entity.Material;
import com.recommend.entity.User;
import com.recommend.entity.UserBehavior;
import com.recommend.entity.UserFavorite;
import com.recommend.entity.UserRating;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserBehaviorRepository;
import com.recommend.repository.UserFavoriteRepository;
import com.recommend.repository.UserRatingRepository;
import com.recommend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserFavoriteRepository favoriteRepository;
    private final UserBehaviorRepository behaviorRepository;
    private final MaterialRepository materialRepository;
    private final UserRatingRepository ratingRepository;
    private final UserPreferenceService preferenceService;

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public User getProfile(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    public User updateProfile(String username, Map<String, String> updates) {
        User user = getProfile(username);
        if (updates.containsKey("targetSchool")) user.setTargetSchool(updates.get("targetSchool"));
        if (updates.containsKey("major")) user.setMajor(updates.get("major"));
        if (updates.containsKey("studyPhase")) user.setStudyPhase(User.StudyPhase.valueOf(updates.get("studyPhase")));
        if (updates.containsKey("email")) user.setEmail(normalizeOptionalEmail(updates.get("email")));
        if (updates.containsKey("phone")) user.setPhone(updates.get("phone"));
        if (updates.containsKey("avatarUrl")) user.setAvatarUrl(updates.get("avatarUrl"));
        return userRepository.save(user);
    }

    public List<Material> getFavorites(Long userId) {
        List<Long> materialIds = favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(UserFavorite::getMaterialId).collect(Collectors.toList());

        Map<Long, Material> materialMap = materialRepository.findActiveByIds(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, m -> m, (a, b) -> a));

        return materialIds.stream()
                .map(materialMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Transactional
    public boolean toggleFavorite(Long userId, Long materialId) {
        if (favoriteRepository.existsByUserIdAndMaterialId(userId, materialId)) {
            favoriteRepository.deleteByUserIdAndMaterialId(userId, materialId);
            return false;
        } else {
            UserFavorite fav = new UserFavorite();
            fav.setUserId(userId);
            fav.setMaterialId(materialId);
            favoriteRepository.save(fav);

            /*
             * 原实现收藏不记行为，导致 user_behaviors 里始终没有 COLLECT 记录，
             * 分析页的「行为趋势」和「转化漏斗」缺一条线。
             * 这里补上埋点（取消收藏不记，与点赞的处理保持一致）。
             */
            UserBehavior behavior = new UserBehavior();
            behavior.setUserId(userId);
            behavior.setMaterialId(materialId);
            behavior.setAction(UserBehavior.Action.COLLECT);
            behaviorRepository.save(behavior);
            return true;
        }
    }

    public List<Map<String, Object>> getHistoryView(Long userId) {
        List<UserBehavior> history = behaviorRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100));
        Set<Long> materialIds = history.stream().map(UserBehavior::getMaterialId).collect(Collectors.toSet());
        Map<Long, Material> materialMap = materialRepository.findAllById(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, m -> m, (a, b) -> a));

        return history.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("materialId", item.getMaterialId());
            row.put("action", item.getAction() != null ? item.getAction().name() : null);
            row.put("score", item.getScore());
            row.put("createdAt", item.getCreatedAt() != null ? item.getCreatedAt().format(DATETIME_FORMATTER) : null);
            Material material = materialMap.get(item.getMaterialId());
            row.put("materialTitle", material != null ? material.getTitle() : "资料已删除");
            return row;
        }).collect(Collectors.toList());
    }

    public List<Map<String, Object>> getRatings(Long userId) {
        List<UserRating> ratings = ratingRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        Set<Long> materialIds = ratings.stream().map(UserRating::getMaterialId).collect(Collectors.toSet());
        Map<Long, Material> materialMap = materialRepository.findAllById(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, m -> m, (a, b) -> a));

        return ratings.stream().map(rating -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", rating.getMaterialId());
            row.put("rating", rating.getRating());
            row.put("updatedAt", rating.getUpdatedAt() != null ? rating.getUpdatedAt().format(DATETIME_FORMATTER) : null);
            Material material = materialMap.get(rating.getMaterialId());
            row.put("materialTitle", material != null ? material.getTitle() : "资料已删除");
            return row;
        }).collect(Collectors.toList());
    }

    @Transactional
    public void deleteRating(Long userId, Long materialId) {
        UserRating rating = ratingRepository.findByUserIdAndMaterialId(userId, materialId)
                .orElseThrow(() -> new IllegalArgumentException("评分记录不存在"));
        ratingRepository.delete(rating);
        refreshMaterialRating(materialId);
    }

    @Transactional
    public void clearHistory(Long userId) {
        behaviorRepository.deleteByUserId(userId);
    }

    @Transactional
    public void deleteHistoryItem(Long userId, Long historyId) {
        long affected = behaviorRepository.deleteByIdAndUserId(historyId, userId);
        if (affected == 0) {
            throw new IllegalArgumentException("历史记录不存在");
        }
    }

    public Map<String, Object> getPreferenceView(Long userId) {
        return preferenceService.getPreferenceView(userId);
    }

    @Transactional
    public Map<String, Object> updatePreferenceView(Long userId, Map<String, Object> updates) {
        return preferenceService.updatePreferenceView(userId, updates);
    }

    private String normalizeOptionalEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void refreshMaterialRating(Long materialId) {
        Material material = materialRepository.findById(materialId).orElse(null);
        if (material == null) {
            return;
        }

        Double avg = ratingRepository.findAvgRatingByMaterialId(materialId);
        Long count = ratingRepository.countByMaterialId(materialId);

        if (avg == null || count == null || count == 0) {
            material.setRatingAvg(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            material.setRatingCount(0);
        } else {
            material.setRatingAvg(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP));
            material.setRatingCount(count.intValue());
        }
        materialRepository.save(material);
    }
}
