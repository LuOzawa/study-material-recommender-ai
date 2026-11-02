package com.recommend.service;

import com.recommend.entity.Material;
import com.recommend.entity.Recommendation;
import com.recommend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final RecommendationRepository recommendationRepository;
    private final MaterialRepository materialRepository;
    private final UserPreferenceService preferenceService;
    /** 用户反馈（点赞/点踩），用于在线重排 */
    private final FeedbackService feedbackService;

    /** 点过「有用」的加权 */
    private static final double FEEDBACK_LIKE_BONUS = 1.5;

    /** 点过「没用」的降权（比偏好权重的 2.0 更重，体现明确否定） */
    private static final double FEEDBACK_DISLIKE_PENALTY = 5.0;

    @Value("${recommendation.python-script}")
    private String pythonScript;

    @Value("${recommendation.top-n:10}")
    private int topN;

    public List<Material> getRecommendations(Long userId) {
        UserPreferenceService.PreferenceData preference = preferenceService.getPreferenceData(userId);
        int fetchSize = Math.max(topN, preference.topN());

        List<Recommendation> recs = recommendationRepository
                .findByUserIdOrderByScoreDesc(userId, PageRequest.of(0, fetchSize));

        // 一次性取出该用户的全部反馈，避免在重排循环里逐条查库
        Map<Long, com.recommend.entity.RecommendationFeedback.FeedbackType> feedbackMap =
                feedbackService.mapByUser(userId);

        if (recs.isEmpty()) {
            List<Material> fallback = applyPreference(getHotMaterials(fetchSize), preference, feedbackMap);
            fallback.forEach(material -> {
                material.setRecommendationAlgorithm("HOT");
                material.setRecommendationScore(null);
            });
            return fallback;
        }

        List<Long> materialIds = recs.stream()
                .map(Recommendation::getMaterialId)
                .collect(Collectors.toList());

        Map<Long, Material> materialMap = materialRepository.findActiveByIds(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, m -> m, (a, b) -> a));

        List<Material> materials = materialIds.stream()
                .map(materialMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, Recommendation> recommendationMap = recs.stream()
                .collect(Collectors.toMap(
                        Recommendation::getMaterialId,
                        rec -> rec,
                        (a, b) -> a
                ));

        materials.forEach(material -> {
            Recommendation rec = recommendationMap.get(material.getId());
            if (rec == null) {
                return;
            }
            if (rec.getAlgorithm() != null) {
                material.setRecommendationAlgorithm(rec.getAlgorithm().name());
            }
            material.setRecommendationScore(rec.getScore());
        });

        return applyPreference(materials, preference, feedbackMap);
    }

    /**
     * Public recommendation list for guests (non-personalized hot materials).
     */
    public List<Material> getGuestRecommendations() {
        List<Material> materials = getHotMaterials(topN);
        materials.forEach(material -> {
            material.setRecommendationAlgorithm("HOT");
            material.setRecommendationScore(null);
        });
        return materials;
    }

    private List<Material> getHotMaterials(int size) {
        return materialRepository.findTopByViewCount(PageRequest.of(0, size));
    }

    private List<Material> applyPreference(List<Material> materials,
                                           UserPreferenceService.PreferenceData preference,
                                           Map<Long, com.recommend.entity.RecommendationFeedback.FeedbackType> feedbackMap) {
        if (materials == null || materials.isEmpty()) {
            return materials;
        }
        Map<Long, com.recommend.entity.RecommendationFeedback.FeedbackType> feedback =
                feedbackMap == null ? Map.of() : feedbackMap;

        List<ScoredMaterial> scored = new java.util.ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            Material material = materials.get(i);
            double score = materials.size() - i;

            if (material.getCategoryId() != null && preference.preferredCategories().contains(material.getCategoryId())) {
                score += 2.0;
            }

            java.util.Set<String> tags = parseTags(material.getTags());
            int preferredMatches = intersectionSize(tags, preference.preferredTags());
            int excludedMatches = intersectionSize(tags, preference.excludedTags());

            score += preferredMatches * 0.9;
            if (excludedMatches > 0) {
                score -= excludedMatches * 2.0;
            }

            // 用户反馈闭环：点过「有用」加权，点过「没用」重罚
            com.recommend.entity.RecommendationFeedback.FeedbackType fb = feedback.get(material.getId());
            if (fb == com.recommend.entity.RecommendationFeedback.FeedbackType.LIKE) {
                score += FEEDBACK_LIKE_BONUS;
            } else if (fb == com.recommend.entity.RecommendationFeedback.FeedbackType.DISLIKE) {
                score -= FEEDBACK_DISLIKE_PENALTY;
            }

            scored.add(new ScoredMaterial(material, score, i));
        }

        List<ScoredMaterial> sorted = scored.stream()
                .sorted((a, b) -> {
                    int byScore = Double.compare(b.score, a.score);
                    if (byScore != 0) return byScore;
                    return Integer.compare(a.originalIndex, b.originalIndex);
                })
                .collect(Collectors.toList());

        // 「不感兴趣」的资料直接剔除；若剔除后为空则回退为不剔除，避免推荐列表变空
        List<Material> filtered = sorted.stream()
                .filter(item -> feedback.get(item.material.getId())
                        != com.recommend.entity.RecommendationFeedback.FeedbackType.DISLIKE)
                .map(item -> item.material)
                .collect(Collectors.toList());
        List<Material> picked = filtered.isEmpty()
                ? sorted.stream().map(item -> item.material).collect(Collectors.toList())
                : filtered;

        return picked.stream()
                .limit(preference.topN())
                .collect(Collectors.toList());
    }

    private java.util.Set<String> parseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Arrays.stream(rawTags.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private int intersectionSize(java.util.Set<String> a, java.util.Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String item : a) {
            if (b.contains(item)) {
                count++;
            }
        }
        return count;
    }

    private static class ScoredMaterial {
        private final Material material;
        private final double score;
        private final int originalIndex;

        private ScoredMaterial(Material material, double score, int originalIndex) {
            this.material = material;
            this.score = score;
            this.originalIndex = originalIndex;
        }
    }

    /**
     * Trigger recommendation refresh for a specific user via Python script.
     */
    public void refreshForUser(Long userId) {
        runPythonScript("--user-id", String.valueOf(userId));
    }

    /**
     * Nightly full recommendation refresh (2:00 AM).
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledRefresh() {
        log.info("Starting scheduled recommendation refresh...");
        runPythonScript();
    }

    private void runPythonScript(String... args) {
        try {
            String[] cmd = new String[2 + args.length];
            cmd[0] = "python";
            cmd[1] = resolvePythonScriptPath();
            System.arraycopy(args, 0, cmd, 2, args.length);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output in background thread to prevent blocking
            Thread reader = new Thread(() -> {
                try (var reader2 = process.inputReader()) {
                    reader2.lines().forEach(line -> log.info("[python] {}", line));
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            int exit = process.waitFor();
            if (exit != 0) {
                log.warn("Python recommendation script exited with code {}", exit);
            }
        } catch (Exception e) {
            log.error("Failed to run recommendation script: {}", e.getMessage());
        }
    }

    private String resolvePythonScriptPath() {
        Path configured = Path.of(pythonScript);
        if (configured.isAbsolute() && Files.exists(configured)) {
            return configured.toString();
        }

        Path userDir = Path.of(System.getProperty("user.dir"));

        Path fromUserDir = userDir.resolve(pythonScript).normalize();
        if (Files.exists(fromUserDir)) {
            return fromUserDir.toString();
        }

        Path inCurrentRoot = userDir.resolve("recommendation").resolve("run_recommendation.py").normalize();
        if (Files.exists(inCurrentRoot)) {
            return inCurrentRoot.toString();
        }

        Path parent = userDir.getParent();
        if (parent != null) {
            Path inParentRoot = parent.resolve("recommendation").resolve("run_recommendation.py").normalize();
            if (Files.exists(inParentRoot)) {
                return inParentRoot.toString();
            }
        }

        log.warn("Recommendation script path not found by fallback. Using raw config: {}", pythonScript);
        return pythonScript;
    }
}
