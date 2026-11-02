package com.recommend.service;

import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserBehaviorRepository;
import com.recommend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** 行为趋势图默认时间窗口（天） */
    private static final int DEFAULT_TREND_DAYS = 14;

    private final UserBehaviorRepository behaviorRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Analytics dashboard payload (single endpoint for all charts).
     */
    public Map<String, Object> getDashboardData() {
        return getDashboardData(DEFAULT_TREND_DAYS);
    }

    /**
     * 看板数据。
     *
     * @param trendDays 行为趋势图的时间窗口（天）。原实现写死 14 天，
     *                  一旦演示数据超过两周，这个图就永远是空白的。
     *                  现在可通过 /api/analytics/dashboard?days=90 指定。
     */
    public Map<String, Object> getDashboardData(int trendDays) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stats", getSummaryStats());
        data.put("hotness", getMaterialHotness());
        data.put("userPhase", getUserStudyPhaseDistribution());
        data.put("actionDistribution", getBehaviorActionDistribution());
        data.put("behaviorTrend", getBehaviorTrend(trendDays));
        data.put("materialStatus", getMaterialStatusDistribution());
        data.put("categoryHotness", getCategoryHotness());
        data.put("categoryQuality", getCategoryQuality());
        data.put("ratingDistribution", getRatingDistribution());
        data.put("interactionFunnel", getInteractionFunnel());
        data.put("feedbackDistribution", getRecommendationFeedbackDistribution());
        return data;
    }

    /**
     * Returns top-20 hottest materials by combined behavior count.
     * Format: [{materialId, title, count}]
     */
    public List<Map<String, Object>> getMaterialHotness() {
        List<Object[]> rows = behaviorRepository.findMaterialHotness(PageRequest.of(0, 20));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Long materialId = ((Number) row[0]).longValue();
            Long count = ((Number) row[1]).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", materialId);
            item.put("count", count);
            materialRepository.findById(materialId).ifPresent(m -> item.put("title", m.getTitle()));
            result.add(item);
        }
        return result;
    }

    /**
     * Returns user distribution by study phase.
     * Format: [{phase, count}]
     */
    public List<Map<String, Object>> getUserStudyPhaseDistribution() {
        List<Object[]> rows = behaviorRepository.countUsersByStudyPhase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("phase", row[0] != null ? row[0].toString() : "UNKNOWN");
            item.put("count", ((Number) row[1]).longValue());
            result.add(item);
        }
        return result;
    }

    /**
     * Returns summary stats for the dashboard.
     */
    public Map<String, Object> getSummaryStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("totalMaterials", materialRepository.count());
        stats.put("totalBehaviors", behaviorRepository.count());
        stats.put("pendingMaterials", queryCount("SELECT COUNT(*) FROM materials WHERE status = 'PENDING'"));
        stats.put("totalRatings", queryCount("SELECT COUNT(*) FROM user_ratings"));
        stats.put("totalFavorites", queryCount("SELECT COUNT(*) FROM user_favorites"));
        stats.put("totalLikes", queryCount("SELECT COUNT(*) FROM material_likes"));
        stats.put("totalComments", queryCount("SELECT COUNT(*) FROM material_comments"));
        stats.put("totalFeedback", queryCount("SELECT COUNT(*) FROM recommendation_feedback"));
        stats.put("avgRating", queryDouble("SELECT COALESCE(ROUND(AVG(rating), 2), 0) FROM user_ratings"));
        return stats;
    }

    /**
     * Behavior volume grouped by action.
     * Format: [{action, count}]
     */
    public List<Map<String, Object>> getBehaviorActionDistribution() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT action, COUNT(*) AS cnt
                FROM user_behaviors
                GROUP BY action
                ORDER BY cnt DESC
                """);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", row.get("action"));
            item.put("count", toLong(row.get("cnt")));
            result.add(item);
        }
        return result;
    }

    /**
     * Behavior trend over N days.
     * Format:
     * [{date, view, like, dislike, collect, rate, download, total}]
     */
    public List<Map<String, Object>> getBehaviorTrend(int days) {
        // 原实现把窗口夹在 7~60 天。演示/答辩时数据往往是几个月前造的，
        // 60 天仍然取不到，趋势图会是空的，因此上限放宽到 365 天。
        int safeDays = Math.max(7, Math.min(days, 365));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day, action, COUNT(*) AS cnt
                FROM user_behaviors
                WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
                GROUP BY day, action
                ORDER BY day ASC
                """, safeDays - 1);

        Map<String, Map<String, Long>> trendMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String day = row.get("day") != null ? row.get("day").toString() : "";
            String action = row.get("action") != null ? row.get("action").toString() : "UNKNOWN";
            long count = toLong(row.get("cnt"));
            trendMap.computeIfAbsent(day, k -> new HashMap<>()).put(action, count);
        }

        LocalDate start = LocalDate.now().minusDays(safeDays - 1L);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < safeDays; i++) {
            String day = start.plusDays(i).format(DAY_FORMATTER);
            Map<String, Long> dayStats = trendMap.getOrDefault(day, Collections.emptyMap());

            long view = dayStats.getOrDefault("VIEW", 0L);
            long like = dayStats.getOrDefault("LIKE", 0L);
            long dislike = dayStats.getOrDefault("DISLIKE", 0L);
            long collect = dayStats.getOrDefault("COLLECT", 0L);
            long rate = dayStats.getOrDefault("RATE", 0L);
            long download = dayStats.getOrDefault("DOWNLOAD", 0L);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day);
            item.put("view", view);
            item.put("like", like);
            item.put("dislike", dislike);
            item.put("collect", collect);
            item.put("rate", rate);
            item.put("download", download);
            item.put("total", view + like + dislike + collect + rate + download);
            result.add(item);
        }

        return result;
    }

    /**
     * Material status distribution.
     * Format: [{status, count}]
     */
    public List<Map<String, Object>> getMaterialStatusDistribution() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT status, COUNT(*) AS cnt
                FROM materials
                GROUP BY status
                """);

        Map<String, Long> countByStatus = new LinkedHashMap<>();
        countByStatus.put("ACTIVE", 0L);
        countByStatus.put("PENDING", 0L);
        countByStatus.put("DELETED", 0L);
        for (Map<String, Object> row : rows) {
            String status = row.get("status") != null ? row.get("status").toString() : "UNKNOWN";
            countByStatus.put(status, toLong(row.get("cnt")));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : countByStatus.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", entry.getKey());
            item.put("count", entry.getValue());
            result.add(item);
        }
        return result;
    }

    /**
     * Category hotness.
     * Format:
     * [{category, materialCount, views, downloads, favorites, likes, comments, hotness}]
     */
    public List<Map<String, Object>> getCategoryHotness() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT
                    COALESCE(c.name, 'UNCLASSIFIED') AS category,
                    COUNT(m.id) AS materialCount,
                    COALESCE(SUM(m.view_count), 0) AS views,
                    COALESCE(SUM(m.download_count), 0) AS downloads,
                    COALESCE(f.favoriteCount, 0) AS favorites,
                    COALESCE(l.likeCount, 0) AS likes,
                    COALESCE(cm.commentCount, 0) AS comments
                FROM materials m
                LEFT JOIN categories c ON c.id = m.category_id
                LEFT JOIN (
                    SELECT m2.category_id AS categoryId, COUNT(*) AS favoriteCount
                    FROM user_favorites uf
                    INNER JOIN materials m2 ON m2.id = uf.material_id
                    GROUP BY m2.category_id
                ) f ON f.categoryId = m.category_id
                LEFT JOIN (
                    SELECT m3.category_id AS categoryId, COUNT(*) AS likeCount
                    FROM material_likes ml
                    INNER JOIN materials m3 ON m3.id = ml.material_id
                    GROUP BY m3.category_id
                ) l ON l.categoryId = m.category_id
                LEFT JOIN (
                    SELECT m4.category_id AS categoryId, COUNT(*) AS commentCount
                    FROM material_comments mc
                    INNER JOIN materials m4 ON m4.id = mc.material_id
                    GROUP BY m4.category_id
                ) cm ON cm.categoryId = m.category_id
                GROUP BY m.category_id, c.name, f.favoriteCount, l.likeCount, cm.commentCount
                ORDER BY views DESC, downloads DESC
                LIMIT 12
                """);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long views = toLong(row.get("views"));
            long downloads = toLong(row.get("downloads"));
            long favorites = toLong(row.get("favorites"));
            long likes = toLong(row.get("likes"));
            long comments = toLong(row.get("comments"));

            long hotness = Math.round(views * 1.0 + downloads * 1.2 + favorites * 3 + likes * 2 + comments * 2);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", row.get("category"));
            item.put("materialCount", toLong(row.get("materialCount")));
            item.put("views", views);
            item.put("downloads", downloads);
            item.put("favorites", favorites);
            item.put("likes", likes);
            item.put("comments", comments);
            item.put("hotness", hotness);
            result.add(item);
        }
        return result;
    }

    /**
     * Category quality view (avg rating + supply).
     * Format: [{category, materialCount, avgRating, ratingUsers}]
     */
    public List<Map<String, Object>> getCategoryQuality() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT
                    COALESCE(c.name, 'UNCLASSIFIED') AS category,
                    COUNT(m.id) AS materialCount,
                    COALESCE(ROUND(AVG(NULLIF(m.rating_avg, 0)), 2), 0) AS avgRating,
                    COALESCE(SUM(m.rating_count), 0) AS ratingUsers
                FROM materials m
                LEFT JOIN categories c ON c.id = m.category_id
                WHERE m.status = 'ACTIVE'
                GROUP BY m.category_id, c.name
                ORDER BY materialCount DESC, avgRating DESC
                LIMIT 12
                """);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", row.get("category"));
            item.put("materialCount", toLong(row.get("materialCount")));
            item.put("avgRating", toDouble(row.get("avgRating")));
            item.put("ratingUsers", toLong(row.get("ratingUsers")));
            result.add(item);
        }
        return result;
    }

    /**
     * Rating distribution 1..5.
     * Format: [{rating, count}]
     */
    public List<Map<String, Object>> getRatingDistribution() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT rating, COUNT(*) AS cnt
                FROM user_ratings
                GROUP BY rating
                ORDER BY rating
                """);

        Map<Integer, Long> countByRating = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            countByRating.put(i, 0L);
        }
        for (Map<String, Object> row : rows) {
            int rating = ((Number) row.get("rating")).intValue();
            countByRating.put(rating, toLong(row.get("cnt")));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : countByRating.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rating", entry.getKey());
            item.put("count", entry.getValue());
            result.add(item);
        }
        return result;
    }

    /**
     * Interaction funnel from existing tables.
     * Format: [{stage, count}]
     */
    public List<Map<String, Object>> getInteractionFunnel() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(stageItem("VIEW", queryCount("SELECT COUNT(*) FROM user_behaviors WHERE action = 'VIEW'")));
        result.add(stageItem("COLLECT", queryCount("SELECT COUNT(*) FROM user_favorites")));
        result.add(stageItem("LIKE", queryCount("SELECT COUNT(*) FROM material_likes")));
        result.add(stageItem("COMMENT", queryCount("SELECT COUNT(*) FROM material_comments")));
        result.add(stageItem("DOWNLOAD", queryCount("SELECT COUNT(*) FROM user_behaviors WHERE action = 'DOWNLOAD'")));
        return result;
    }

    /**
     * Recommendation feedback distribution.
     * Format: [{feedback, count}]
     */
    public List<Map<String, Object>> getRecommendationFeedbackDistribution() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT feedback, COUNT(*) AS cnt
                FROM recommendation_feedback
                GROUP BY feedback
                """);

        Map<String, Long> countByFeedback = new LinkedHashMap<>();
        countByFeedback.put("LIKE", 0L);
        countByFeedback.put("DISLIKE", 0L);
        for (Map<String, Object> row : rows) {
            String feedback = row.get("feedback") != null ? row.get("feedback").toString() : "UNKNOWN";
            countByFeedback.put(feedback, toLong(row.get("cnt")));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : countByFeedback.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("feedback", entry.getKey());
            item.put("count", entry.getValue());
            result.add(item);
        }
        return result;
    }

    /**
     * Clean and deduplicate behavior logs.
     * Rule: keep the earliest row for the same (user_id, material_id, action) combination.
     */
    @Transactional
    public Map<String, Object> cleanBehaviorLogs() {
        Number before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_behaviors", Number.class);
        int removed = jdbcTemplate.update("""
                DELETE ub1
                FROM user_behaviors ub1
                INNER JOIN user_behaviors ub2
                    ON ub1.user_id = ub2.user_id
                   AND ub1.material_id = ub2.material_id
                   AND ub1.action = ub2.action
                   AND ub1.id > ub2.id
                """);
        Number after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_behaviors", Number.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("before", before != null ? before.longValue() : 0L);
        result.put("removed", removed);
        result.put("after", after != null ? after.longValue() : 0L);
        return result;
    }

    private Map<String, Object> stageItem(String stage, long count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stage", stage);
        item.put("count", count);
        return item;
    }

    private long queryCount(String sql) {
        Number n = jdbcTemplate.queryForObject(sql, Number.class);
        return n != null ? n.longValue() : 0L;
    }

    private double queryDouble(String sql) {
        Number n = jdbcTemplate.queryForObject(sql, Number.class);
        return n != null ? n.doubleValue() : 0D;
    }

    private long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0D;
    }
}
