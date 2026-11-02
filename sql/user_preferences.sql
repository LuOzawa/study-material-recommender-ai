-- User recommendation preference table
CREATE TABLE IF NOT EXISTS user_preferences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    preferred_categories VARCHAR(500) DEFAULT NULL COMMENT 'Comma-separated category IDs',
    preferred_tags VARCHAR(500) DEFAULT NULL COMMENT 'Comma-separated preferred tags',
    excluded_tags VARCHAR(500) DEFAULT NULL COMMENT 'Comma-separated tags to avoid',
    top_n INT NOT NULL DEFAULT 10 COMMENT 'Preferred recommendation count',
    auto_refresh TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Auto refresh recommendations',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_pref_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Seed preference data synced from current database snapshot.
INSERT INTO user_preferences (id, user_id, preferred_categories, preferred_tags, excluded_tags, top_n, auto_refresh, updated_at)
VALUES (1, 2, '3', NULL, NULL, 10, 0, '2026-03-27 14:23:34')
ON DUPLICATE KEY UPDATE
preferred_categories = VALUES(preferred_categories),
preferred_tags = VALUES(preferred_tags),
excluded_tags = VALUES(excluded_tags),
top_n = VALUES(top_n),
auto_refresh = VALUES(auto_refresh),
updated_at = VALUES(updated_at);
