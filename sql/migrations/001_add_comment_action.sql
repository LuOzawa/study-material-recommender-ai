-- Existing databases: allow the COMMENT behavior added in 2026-11.
ALTER TABLE user_behaviors DROP CONSTRAINT IF EXISTS user_behaviors_action_check;
ALTER TABLE user_behaviors ADD CONSTRAINT user_behaviors_action_check
    CHECK (action IN ('VIEW', 'LIKE', 'DISLIKE', 'COLLECT', 'RATE', 'DOWNLOAD', 'COMMENT'));
