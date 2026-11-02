"""
往数据库灌入模拟用户与交互数据（用于把评测做到有统计意义的规模）。

背景
----
真实库里只有 4 个用户、20 条评分、102 条行为。留一法评测下只有 3 个用户
能参与，指标波动极大（一个用户命中与否就能让 Recall 变化 33%），
无法支撑论文里的对比实验。

本脚本按「用户偏好若干学科 → 主要与该学科资料交互」的规则生成一批模拟数据，
再配合 `evaluate_offline.py --source db` 就能得到规模像样的评测表。

安全设计
--------
1. **默认只预览，不写库**。必须显式加 `--apply` 才会真写。
2. **只 INSERT，不 UPDATE / DELETE 任何已有数据**。
   评分与反馈用 ON DUPLICATE KEY UPDATE，重复执行幂等。
3. **可一键撤销**：所有模拟用户的 username 统一带前缀（默认 `sim_`），
   由于 user_ratings / user_behaviors / recommendation_feedback 三张表都是
   `ON DELETE CASCADE` 外键，执行

       python seed_synthetic_users.py --cleanup --apply

   只删 users 表里 `username LIKE 'sim_%'` 的行，其余数据会级联清掉，
   不会碰到真实用户。

用法
----
    python seed_synthetic_users.py                        # 预览（不写库）
    python seed_synthetic_users.py --users 200 --apply    # 真正写入
    python seed_synthetic_users.py --cleanup --apply      # 清除全部模拟数据

灌完之后跑评测：
    python evaluate_offline.py --source db
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Dict, List, Tuple

import numpy as np

# 与项目里已有数据一致：BCrypt 哈希的是一个真实可登录的口令，
# 未安装 bcrypt 包时回退到它（对应明文 123456，已在本项目登录接口验证通过）。
FALLBACK_BCRYPT_HASH = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi"
FALLBACK_PASSWORD = "123456"

STUDY_PHASES = ["PREPARATION", "INITIAL", "INTENSIVE", "REVIEW"]

TARGET_SCHOOLS = ["清华大学", "北京大学", "浙江大学", "复旦大学", "上海交通大学",
                  "南京大学", "武汉大学", "中山大学", "四川大学", "西安交通大学"]
MAJORS = ["计算机科学与技术", "软件工程", "电子信息", "自动化", "数学与应用数学",
          "统计学", "通信工程", "人工智能"]

POSITIVE_ACTIONS = ["VIEW", "LIKE", "COLLECT", "DOWNLOAD"]


def get_connection():
    import pymysql

    cfg = {
        "host": os.getenv("DB_HOST", "localhost"),
        "port": int(os.getenv("DB_PORT", "3306")),
        "user": os.getenv("DB_USERNAME", "root"),
        "password": os.getenv("DB_PASSWORD", ""),
        "database": os.getenv("DB_NAME", "recommend_db"),
        "charset": "utf8mb4",
    }
    conn = pymysql.connect(**cfg)
    if cfg["database"] != "recommend_db":
        print(f"⚠️  目标库不是 recommend_db，而是 {cfg['database']}")
    return conn


def resolve_password_hash(password: str) -> Tuple[str, str]:
    """返回 (hash, 实际生效的明文口令)。"""
    try:
        import bcrypt  # 可选依赖
        hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10, prefix=b"2a")).decode()
        return hashed, password
    except ImportError:
        return FALLBACK_BCRYPT_HASH, FALLBACK_PASSWORD


def load_materials(conn) -> Tuple[List[int], Dict[int, str]]:
    """取出 ACTIVE 资料，以及每条资料的学科（取 tags 的第一段）。"""
    with conn.cursor() as cur:
        cur.execute("SELECT id, tags FROM materials WHERE status = 'ACTIVE' ORDER BY id")
        rows = cur.fetchall()
    ids = [int(r[0]) for r in rows]
    subject = {}
    for mid, tags in rows:
        first = (tags or "").split(",")[0].strip()
        subject[int(mid)] = first or "其他"
    return ids, subject


def generate(n_users: int, material_ids: List[int], material_subject: Dict[int, str], seed: int):
    """生成模拟用户、评分、行为、反馈四份数据。"""
    rng = np.random.default_rng(seed)

    subjects = sorted(set(material_subject.values()))
    # 每个学科下有哪些资料
    by_subject: Dict[str, List[int]] = {s: [] for s in subjects}
    for mid in material_ids:
        by_subject.setdefault(material_subject[mid], []).append(mid)

    users, ratings, behaviors, feedback = [], [], [], []

    for i in range(1, n_users + 1):
        username = f"{ARGS.prefix}{i:04d}"

        # 每个用户偏好 1~2 个学科
        n_pref = int(rng.integers(1, 3))
        preferred = list(rng.choice(subjects, size=min(n_pref, len(subjects)), replace=False))

        users.append({
            "username": username,
            "email": f"{username}@example.com",
            "target_school": str(rng.choice(TARGET_SCHOOLS)),
            "major": str(rng.choice(MAJORS)),
            "study_phase": str(rng.choice(STUDY_PHASES)),
        })

        # 交互数量：6~12 条，最多不超过资料总数
        n_interact = int(rng.integers(6, 13))
        n_interact = min(n_interact, len(material_ids))

        # 偏好学科的权重更高
        weights = np.array([
            4.0 if material_subject[mid] in preferred else 1.0 for mid in material_ids
        ], dtype=float)
        weights /= weights.sum()
        picked = rng.choice(material_ids, size=n_interact, replace=False, p=weights)

        for mid in picked:
            mid = int(mid)
            liked = material_subject[mid] in preferred
            rating = int(rng.integers(4, 6)) if liked else int(rng.integers(1, 4))
            ratings.append((username, mid, rating))

            if rating >= 4:
                acts = rng.choice(POSITIVE_ACTIONS,
                                  size=int(rng.integers(1, 4)), replace=False)
                for a in acts:
                    behaviors.append((username, mid, str(a)))
            else:
                behaviors.append((username, mid, "VIEW"))

            # 约 15% 的交互附带推荐反馈；低分资料更容易被点「没用」
            if rng.random() < 0.15:
                feedback.append((username, mid, "LIKE" if rating >= 4 else "DISLIKE"))

    return users, ratings, behaviors, feedback


def existing_sim_users(conn, prefix: str) -> int:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM users WHERE username LIKE %s", (prefix + "%",))
        return int(cur.fetchone()[0])


def cleanup(conn, prefix: str, apply: bool) -> None:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM users WHERE username LIKE %s", (prefix + "%",))
        n = int(cur.fetchone()[0])
    print(f"将删除 {n} 个以 '{prefix}' 开头的模拟用户，"
          f"其评分/行为/反馈会因外键 CASCADE 一并删除。")
    if not apply:
        print("（预览模式，未执行。加 --apply 生效）")
        return
    with conn.cursor() as cur:
        cur.execute("DELETE FROM users WHERE username LIKE %s", (prefix + "%",))
    conn.commit()
    print(f"已删除 {n} 个模拟用户及其关联数据。")


def seed(conn, apply: bool, users, ratings, behaviors, feedback, password_hash, password):
    print(f"待写入：用户 {len(users)} 个 / 评分 {len(ratings)} 条 / "
          f"行为 {len(behaviors)} 条 / 反馈 {len(feedback)} 条")

    if not apply:
        print("（预览模式，未写库。确认无误后加 --apply）")
        if users:
            print("\n样例用户：")
            for u in users[:3]:
                print("  ", u)
            print("样例评分：", ratings[:3])
            print("样例行为：", behaviors[:3])
        return

    with conn.cursor() as cur:
        # 1) 用户：已存在则跳过（保证幂等，且绝不改动已有用户的任何字段）
        cur.executemany(
            """
            INSERT INTO users (username, password, role, email, target_school, major,
                               study_phase, enabled)
            VALUES (%s, %s, 'USER', %s, %s, %s, %s, 1)
            ON DUPLICATE KEY UPDATE username = username
            """,
            [(u["username"], password_hash, u["email"], u["target_school"],
              u["major"], u["study_phase"]) for u in users],
        )
        print(f"  用户写入完成（跳过已存在的）")

        # 2) 建立 username -> id 映射
        cur.execute("SELECT id, username FROM users WHERE username LIKE %s", (ARGS.prefix + "%",))
        id_map = {name: int(uid) for uid, name in cur.fetchall()}

        # 3) 评分
        cur.executemany(
            """
            INSERT INTO user_ratings (user_id, material_id, rating)
            VALUES (%s, %s, %s)
            ON DUPLICATE KEY UPDATE rating = VALUES(rating)
            """,
            [(id_map[u], mid, r) for u, mid, r in ratings if u in id_map],
        )
        print("  评分写入完成")

        # 4) 行为（表上没有唯一键，先按模拟用户清空再插入，保证幂等）
        if id_map:
            cur.execute("DELETE FROM user_behaviors WHERE user_id IN ("
                        + ",".join(["%s"] * len(id_map)) + ")", tuple(id_map.values()))
        cur.executemany(
            "INSERT INTO user_behaviors (user_id, material_id, action) VALUES (%s, %s, %s)",
            [(id_map[u], mid, a) for u, mid, a in behaviors if u in id_map],
        )
        print("  行为写入完成")

        # 5) 反馈
        cur.executemany(
            """
            INSERT INTO recommendation_feedback (user_id, material_id, feedback)
            VALUES (%s, %s, %s)
            ON DUPLICATE KEY UPDATE feedback = VALUES(feedback)
            """,
            [(id_map[u], mid, f) for u, mid, f in feedback if u in id_map],
        )
        print("  反馈写入完成")

    conn.commit()
    print(f"\n✅ 写入完成。模拟用户口令统一为：{password}")


def main() -> int:
    global ARGS
    parser = argparse.ArgumentParser(description="灌入模拟用户与交互数据（用于推荐评测）")
    parser.add_argument("--users", type=int, default=200, help="模拟用户数")
    parser.add_argument("--seed", type=int, default=42, help="随机种子（保证可复现）")
    parser.add_argument("--prefix", default="sim_", help="模拟用户名的前缀（用于识别与清理）")
    parser.add_argument("--password", default="123456", help="模拟用户口令")
    parser.add_argument("--apply", action="store_true", help="真正写库（默认只预览）")
    parser.add_argument("--cleanup", action="store_true", help="删除全部模拟用户及其关联数据")
    ARGS = parser.parse_args()

    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT DATABASE()")
            db = cur.fetchone()[0]
        print(f"目标数据库：{db}")
        print(f"当前模拟用户数：{existing_sim_users(conn, ARGS.prefix)}")

        if ARGS.cleanup:
            cleanup(conn, ARGS.prefix, ARGS.apply)
            return 0

        material_ids, material_subject = load_materials(conn)
        if not material_ids:
            print("没有 ACTIVE 资料，无法生成数据。")
            return 1
        print(f"可用资料：{len(material_ids)} 条，"
              f"学科：{', '.join(sorted(set(material_subject.values())))}")

        users, ratings, behaviors, feedback = generate(
            ARGS.users, material_ids, material_subject, ARGS.seed)

        password_hash, password = resolve_password_hash(ARGS.password)
        if password != ARGS.password:
            print(f"⚠️  未安装 bcrypt 包，口令回退为 {password}"
                  f"（pip install bcrypt 可自定义）")

        seed(conn, ARGS.apply, users, ratings, behaviors, feedback, password_hash, password)

        if ARGS.apply:
            print("\n下一步：")
            print("  python evaluate_offline.py --source db        # 跑评测")
            print(f"  python seed_synthetic_users.py --cleanup --apply   # 撤销")
        return 0
    finally:
        conn.close()


ARGS = None

if __name__ == "__main__":
    sys.exit(main())
