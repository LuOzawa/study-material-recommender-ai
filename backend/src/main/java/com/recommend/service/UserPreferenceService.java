package com.recommend.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${recommendation.top-n:10}")
    private int defaultTopN;

    @PostConstruct
    public void initTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_preferences (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL UNIQUE,
                    preferred_categories VARCHAR(500) DEFAULT NULL,
                    preferred_tags VARCHAR(500) DEFAULT NULL,
                    excluded_tags VARCHAR(500) DEFAULT NULL,
                    top_n INT NOT NULL DEFAULT 10,
                    auto_refresh TINYINT(1) NOT NULL DEFAULT 0,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    CONSTRAINT fk_pref_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                )
                """);
    }

    public PreferenceData getPreferenceData(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT preferred_categories, preferred_tags, excluded_tags, top_n, auto_refresh
                FROM user_preferences WHERE user_id = ?
                """, userId);

        if (rows.isEmpty()) {
            return defaultPreference();
        }

        Map<String, Object> row = rows.get(0);
        return new PreferenceData(
                parseLongSet(row.get("preferred_categories")),
                parseStringSet(row.get("preferred_tags")),
                parseStringSet(row.get("excluded_tags")),
                parseTopN(row.get("top_n"), defaultTopN),
                parseBoolean(row.get("auto_refresh"), false)
        );
    }

    public Map<String, Object> getPreferenceView(Long userId) {
        return toView(getPreferenceData(userId));
    }

    @Transactional
    public Map<String, Object> updatePreferenceView(Long userId, Map<String, Object> updates) {
        PreferenceData current = getPreferenceData(userId);

        Set<Long> preferredCategories = updates.containsKey("preferredCategories")
                ? parseLongSet(updates.get("preferredCategories"))
                : current.preferredCategories();

        Set<String> preferredTags = updates.containsKey("preferredTags")
                ? parseStringSet(updates.get("preferredTags"))
                : current.preferredTags();

        Set<String> excludedTags = updates.containsKey("excludedTags")
                ? parseStringSet(updates.get("excludedTags"))
                : current.excludedTags();

        int topN = updates.containsKey("topN")
                ? parseTopN(updates.get("topN"), current.topN())
                : current.topN();

        boolean autoRefresh = updates.containsKey("autoRefresh")
                ? parseBoolean(updates.get("autoRefresh"), current.autoRefresh())
                : current.autoRefresh();

        jdbcTemplate.update("""
                INSERT INTO user_preferences
                    (user_id, preferred_categories, preferred_tags, excluded_tags, top_n, auto_refresh, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    preferred_categories = VALUES(preferred_categories),
                    preferred_tags = VALUES(preferred_tags),
                    excluded_tags = VALUES(excluded_tags),
                    top_n = VALUES(top_n),
                    auto_refresh = VALUES(auto_refresh),
                    updated_at = NOW()
                """,
                userId,
                joinLongSet(preferredCategories),
                joinStringSet(preferredTags),
                joinStringSet(excludedTags),
                topN,
                autoRefresh ? 1 : 0
        );

        PreferenceData updated = new PreferenceData(preferredCategories, preferredTags, excludedTags, topN, autoRefresh);
        return toView(updated);
    }

    private PreferenceData defaultPreference() {
        return new PreferenceData(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), defaultTopN, false);
    }

    private Map<String, Object> toView(PreferenceData data) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("preferredCategories", new ArrayList<>(data.preferredCategories()));
        view.put("preferredTags", new ArrayList<>(data.preferredTags()));
        view.put("excludedTags", new ArrayList<>(data.excludedTags()));
        view.put("topN", data.topN());
        view.put("autoRefresh", data.autoRefresh());
        return view;
    }

    private Set<Long> parseLongSet(Object raw) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (raw == null) return result;

        if (raw instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item == null) continue;
                try {
                    result.add(Long.parseLong(item.toString().trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            return result;
        }

        String text = raw.toString().trim();
        if (text.isEmpty()) return result;
        for (String part : text.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                result.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private Set<String> parseStringSet(Object raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null) return result;

        if (raw instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item == null) continue;
                String token = item.toString().trim().toLowerCase();
                if (!token.isEmpty()) result.add(token);
            }
            return result;
        }

        String text = raw.toString().trim();
        if (text.isEmpty()) return result;
        for (String part : text.split(",")) {
            String token = part.trim().toLowerCase();
            if (!token.isEmpty()) result.add(token);
        }
        return result;
    }

    private int parseTopN(Object raw, int fallback) {
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.toString().trim());
            return Math.max(5, Math.min(50, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean parseBoolean(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(raw.toString().trim());
    }

    private String joinLongSet(Set<Long> values) {
        if (values == null || values.isEmpty()) return null;
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String joinStringSet(Set<String> values) {
        if (values == null || values.isEmpty()) return null;
        return String.join(",", values);
    }

    public record PreferenceData(
            Set<Long> preferredCategories,
            Set<String> preferredTags,
            Set<String> excludedTags,
            int topN,
            boolean autoRefresh
    ) {}
}
