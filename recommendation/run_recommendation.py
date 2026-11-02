"""
Recommendation Engine Entry Point

Reads data from MySQL, runs UserCF + ItemCF + TF-IDF + SVD,
merges scores, and writes results back to the recommendations table.

Usage:
    python run_recommendation.py [--user-id USER_ID]

Options:
    --user-id: Refresh recommendations for a specific user only (optional)
"""

import argparse
import sys
import logging
import os
import pandas as pd

from usercf import compute_usercf
from itemcf import compute_itemcf
from tfidf_recommend import compute_tfidf
from svd_recommend import compute_svd

# ---------------------------------------------------------------------------
# Configuration - matches application.yml
# ---------------------------------------------------------------------------
DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "3306")),
    "user": os.getenv("DB_USERNAME", "root"),
    # 口令不入仓，请通过环境变量 DB_PASSWORD 提供
    "password": os.getenv("DB_PASSWORD", ""),
    "database": os.getenv("DB_NAME", "recommend_db"),
    "charset": "utf8mb4",
}

# Weight blending: USERCF * w1 + ITEMCF * w2 + TFIDF * w3 + SVD * w4
WEIGHTS = {"usercf": 0.3, "itemcf": 0.3, "tfidf": 0.2, "svd": 0.2}
TOP_N = 20  # recommendations to store per user

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)


def get_connection():
    # 惰性导入：纯离线评测（evaluate_offline.py --source synthetic）不需要数据库驱动
    import pymysql
    return pymysql.connect(**DB_CONFIG)


def load_ratings(conn) -> pd.DataFrame:
    sql = "SELECT user_id, material_id, rating FROM user_ratings"
    return pd.read_sql(sql, conn)


def load_behaviors(conn) -> pd.DataFrame:
    sql = "SELECT user_id, material_id, action FROM user_behaviors"
    return pd.read_sql(sql, conn)


def load_materials(conn) -> pd.DataFrame:
    sql = "SELECT id, title, description, tags FROM materials WHERE status = 'ACTIVE'"
    return pd.read_sql(sql, conn)


def _diversify_per_user(merged_df: pd.DataFrame, top_n: int) -> pd.DataFrame:
    """
    Add one user-specific exploration slot to avoid identical recommendation lists
    across users when candidates are highly overlapped.
    """
    rows = []
    core_size = max(top_n - 1, 1)

    for user_id, group in merged_df.groupby("user_id"):
        ranked = group.sort_values("score", ascending=False).reset_index(drop=True)
        if len(ranked) <= top_n:
            selected = ranked.head(top_n).copy()
            selected["algorithm"] = "HYBRID"
            rows.append(selected)
            continue

        stable = ranked.head(core_size).copy()
        pool = ranked.iloc[core_size:].reset_index(drop=True)

        # Deterministic user-specific index so each user gets a slightly different tail item.
        explore_idx = (int(user_id) * 2654435761) % len(pool)
        explore = pool.iloc[[explore_idx]].copy()

        selected = pd.concat([stable, explore], ignore_index=True)
        if len(selected) < top_n:
            selected_mids = set(selected["material_id"].tolist())
            fill = pool[~pool["material_id"].isin(selected_mids)].head(top_n - len(selected))
            selected = pd.concat([selected, fill], ignore_index=True)

        selected = selected.head(top_n).copy()
        selected["algorithm"] = "HYBRID"
        rows.append(selected)

    if not rows:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])
    return pd.concat(rows, ignore_index=True)


def blend_scores(*dfs) -> pd.DataFrame:
    """
    Normalize + weight + blend multiple algorithm outputs into one score per
    (user_id, material_id) pair, WITHOUT the final diversification step.

    Split out of ``merge_recommendations`` so that the offline evaluator can
    reuse exactly the same blending rules as production.
    """
    combined = pd.concat(dfs, ignore_index=True)
    if combined.empty:
        return combined

    # Normalize scores per algorithm to [0, 1]
    for algo in combined["algorithm"].unique():
        mask = combined["algorithm"] == algo
        max_score = combined.loc[mask, "score"].max()
        if max_score > 0:
            combined.loc[mask, "score"] = combined.loc[mask, "score"] / max_score

    algo_weights = {
        "USERCF": WEIGHTS["usercf"],
        "ITEMCF": WEIGHTS["itemcf"],
        "TFIDF": WEIGHTS["tfidf"],
        "SVD": WEIGHTS["svd"],
    }
    combined["weighted_score"] = combined.apply(
        lambda r: r["score"] * algo_weights.get(r["algorithm"], 0.2), axis=1
    )

    # Small deterministic jitter to break ties consistently per user.
    combined["weighted_score"] = combined.apply(
        lambda r: r["weighted_score"]
        + (((int(r["user_id"]) * 73856093 + int(r["material_id"]) * 19349663) % 997) / 1_000_000),
        axis=1,
    )

    # Sum weighted scores per user-material pair
    merged = (
        combined.groupby(["user_id", "material_id"])["weighted_score"]
        .sum()
        .reset_index()
        .rename(columns={"weighted_score": "score"})
    )
    merged["score"] = merged["score"].round(6)
    return merged


def merge_recommendations(*dfs) -> pd.DataFrame:
    """Blend algorithm outputs and apply the production diversification step."""
    merged = blend_scores(*dfs)
    if merged.empty:
        return merged
    return _diversify_per_user(merged, TOP_N)


def save_recommendations(conn, rec_df: pd.DataFrame, user_id=None):
    cursor = conn.cursor()
    try:
        if user_id:
            cursor.execute("DELETE FROM recommendations WHERE user_id = %s", (user_id,))
        else:
            cursor.execute("DELETE FROM recommendations")

        if rec_df.empty:
            conn.commit()
            log.info("No recommendations to save.")
            return

        rows = rec_df[["user_id", "material_id", "score", "algorithm"]].values.tolist()
        sql = """
            INSERT INTO recommendations (user_id, material_id, score, algorithm, created_at)
            VALUES (%s, %s, %s, %s, NOW())
            ON DUPLICATE KEY UPDATE score = VALUES(score), algorithm = VALUES(algorithm), created_at = NOW()
        """
        cursor.executemany(sql, rows)
        conn.commit()
        log.info("Saved %d recommendations.", len(rows))
    finally:
        cursor.close()


def run(user_id=None):
    log.info("Starting recommendation engine (user_id=%s)...", user_id)
    conn = get_connection()

    try:
        ratings_df = load_ratings(conn)
        behaviors_df = load_behaviors(conn)
        materials_df = load_materials(conn)

        log.info(
            "Loaded %d ratings, %d behaviors, %d materials.",
            len(ratings_df),
            len(behaviors_df),
            len(materials_df),
        )

        usercf_df = compute_usercf(ratings_df)
        log.info("UserCF produced %d recommendations.", len(usercf_df))

        itemcf_df = compute_itemcf(ratings_df)
        log.info("ItemCF produced %d recommendations.", len(itemcf_df))

        tfidf_df = compute_tfidf(materials_df, behaviors_df)
        log.info("TF-IDF produced %d recommendations.", len(tfidf_df))

        svd_df = compute_svd(ratings_df)
        log.info("SVD produced %d recommendations.", len(svd_df))

        merged = merge_recommendations(usercf_df, itemcf_df, tfidf_df, svd_df)
        log.info("Merged: %d final recommendations.", len(merged))

        if user_id is not None:
            merged = merged[merged["user_id"] == user_id].reset_index(drop=True)
            log.info("Filtered to user %s: %d recommendations.", user_id, len(merged))

        save_recommendations(conn, merged, user_id)
        log.info("Done.")
    finally:
        conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--user-id", type=int, default=None, help="Refresh for specific user")
    args = parser.parse_args()
    try:
        run(args.user_id)
    except Exception as e:
        log.error("Recommendation engine failed: %s", e)
        sys.exit(1)
