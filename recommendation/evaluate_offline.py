"""
推荐效果离线评测（leave-one-out）。

用途
----
给论文提供「推荐算法的对比实验数据」。此前项目里没有任何离线指标，
只能凭感觉说"融合效果更好"；本脚本用留一法把这句话量化。

评测口径
--------
1. 对每个用户，从ta的**正反馈**里留出 1 个物品（rating≥3 或 LIKE/COLLECT/DOWNLOAD），
   其余作为训练集 —— 即 leave-one-out。
2. 用训练集分别跑 4 个单算法 + 融合算法，各取 Top-N。
3. 看留出的那个物品是否出现在 Top-K 里，统计：
   Precision@K / Recall@K / HitRate@K / NDCG@K / Coverage。

数据来源
--------
--source db          从 MySQL 读真实数据（user_ratings / user_behaviors / materials /
                     recommendation_feedback）
--source synthetic   自动生成可控规模的合成数据，**不依赖数据库**

  真实数据只有 4 个用户、31 条资料、20 条评分，留一法下每个用户只有 1 个
  相关物品，指标波动极大，统计意义有限。答辩时若需要较稳的曲线，
  用 --source synthetic --synthetic-users 200 生成大规模数据跑一张表，
  并在论文里明确标注是合成数据。

用法
----
    python evaluate_offline.py                          # 真实数据
    python evaluate_offline.py --source synthetic -n 200
    python evaluate_offline.py --k 5,10 --out eval.csv  # 同时导出 CSV

依赖：pandas / numpy / scikit-learn（见 requirements.txt）
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Dict, List, Tuple

import numpy as np
import pandas as pd

from itemcf import compute_itemcf
from metrics import evaluate
from run_recommendation import WEIGHTS, blend_scores, merge_recommendations
from svd_recommend import compute_svd
from tfidf_recommend import compute_tfidf
from usercf import compute_usercf

# 行为 -> 隐式评分，用于把埋点数据折算成可以喂给协同过滤的评分矩阵
ACTION_TO_RATING = {
    "VIEW": 1,
    "DOWNLOAD": 2.5,
    "RATE": 3.5,
    "LIKE": 4,
    "COLLECT": 4,
    "DISLIKE": 1,
}

POSITIVE_ACTIONS = {"LIKE", "COLLECT", "DOWNLOAD", "RATE"}

# 与 Java 端 RecommendationService 保持一致的反馈权重
FEEDBACK_LIKE_BONUS = 1.5
FEEDBACK_DISLIKE_PENALTY = 5.0


# ---------------------------------------------------------------------------
# 数据装配
# ---------------------------------------------------------------------------

def build_rating_matrix(ratings_df: pd.DataFrame, behaviors_df: pd.DataFrame) -> pd.DataFrame:
    """
    把「显式评分」与「行为埋点」合并成统一的 (user_id, material_id, rating)。

    显式评分优先；没有评分的行为按 ACTION_TO_RATING 折算。
    这是把隐式反馈喂给基于评分的协同过滤的常见做法，论文里需要说明这一点。
    """
    implicit = behaviors_df.copy()
    implicit["rating"] = implicit["action"].map(ACTION_TO_RATING)
    implicit = implicit.dropna(subset=["rating"])[["user_id", "material_id", "rating"]]

    explicit = ratings_df[["user_id", "material_id", "rating"]].copy()

    merged = pd.concat([implicit, explicit], ignore_index=True)
    # 同一 (user, item) 取最高分：显式评分通常高于隐式折算值
    merged = (merged.groupby(["user_id", "material_id"], as_index=False)["rating"].max())
    return merged


def build_ground_truth(ratings_df: pd.DataFrame,
                       behaviors_df: pd.DataFrame,
                       seed: int,
                       min_interactions: int) -> Tuple[Dict[int, List[int]], Dict[int, set]]:
    """
    为每个用户留出 1 个正反馈物品。

    Returns:
        ground_truth: {user_id: [held_out_material_id]}
        holdout_items: {user_id: {(material_id, ...)}} 用于训练集剔除
    """
    rng = np.random.default_rng(seed)

    positive = {}
    for uid, group in ratings_df.groupby("user_id"):
        items = group[group["rating"] >= 3]["material_id"].astype(int).tolist()
        if items:
            positive[int(uid)] = set(items)

    for uid, group in behaviors_df.groupby("user_id"):
        items = group[group["action"].isin(POSITIVE_ACTIONS)]["material_id"].astype(int).tolist()
        if items:
            positive.setdefault(int(uid), set()).update(int(x) for x in items)

    ground_truth: Dict[int, List[int]] = {}
    holdout_items: Dict[int, set] = {}

    for uid, items in positive.items():
        if len(items) < min_interactions:
            continue  # 交互太少的用户无法训练，跳过
        chosen = int(rng.choice(sorted(items)))
        ground_truth[uid] = [chosen]
        holdout_items[uid] = {chosen}

    return ground_truth, holdout_items


def drop_holdout(df: pd.DataFrame, holdout_items: Dict[int, set], key="material_id") -> pd.DataFrame:
    """从训练集里剔除被留出的物品。"""
    mask = [
        int(row["material_id"]) not in holdout_items.get(int(row["user_id"]), set())
        for _, row in df.iterrows()
    ]
    return df[mask].reset_index(drop=True)


# ---------------------------------------------------------------------------
# 合成数据
# ---------------------------------------------------------------------------

SUBJECTS = ["数学", "英语", "政治", "专业课", "408", "线代", "概率", "写作"]
TAG_POOL = ["真题", "讲义", "习题", "模板", "速记", "冲刺", "基础", "强化", "押题", "外刊"]


def synthesize(n_users: int, n_items: int, seed: int):
    """生成一份规模可控、带明确偏好的合成数据，用于跑出较稳的指标曲线。"""
    rng = np.random.default_rng(seed)

    materials = []
    for i in range(1, n_items + 1):
        subject = SUBJECTS[i % len(SUBJECTS)]
        tags = rng.choice(TAG_POOL, size=3, replace=False)
        materials.append({
            "id": i,
            "title": f"{subject}资料{i:03d}",
            "description": f"关于{subject}的{'、'.join(tags)}类学习资料",
            "tags": ",".join([subject] + list(tags)),
        })
    materials_df = pd.DataFrame(materials)

    item_subject = {m["id"]: m["title"].split("资料")[0] for m in materials}

    rating_rows, behavior_rows, feedback_rows = [], [], []

    for uid in range(1, n_users + 1):
        # 每个用户偏好 2 个学科
        liked_subjects = rng.choice(SUBJECTS, size=2, replace=False)
        liked_subjects = set(liked_subjects.tolist())

        n_interact = int(rng.integers(6, min(13, n_items)))
        candidates = np.arange(1, n_items + 1)

        weights = np.array([
            4.0 if item_subject[i] in liked_subjects else 1.0 for i in candidates
        ], dtype=float)
        weights = weights / weights.sum()
        picked = rng.choice(candidates, size=n_interact, replace=False, p=weights)

        for mid in picked:
            mid = int(mid)
            affinity = item_subject[mid] in liked_subjects
            rating = int(rng.integers(4, 6)) if affinity else int(rng.integers(1, 4))
            rating_rows.append({"user_id": uid, "material_id": mid, "rating": rating})

            if rating >= 4:
                for action in rng.choice(["VIEW", "LIKE", "COLLECT", "DOWNLOAD"],
                                         size=int(rng.integers(1, 4)), replace=False):
                    behavior_rows.append({"user_id": uid, "material_id": mid,
                                          "action": str(action)})
            else:
                behavior_rows.append({"user_id": uid, "material_id": mid, "action": "VIEW"})

            # 约 15% 的交互附带推荐反馈，且低分物品更容易被点「没用」
            if rng.random() < 0.15:
                fb = "LIKE" if rating >= 4 else "DISLIKE"
                feedback_rows.append({"user_id": uid, "material_id": mid, "feedback": fb})

    return (
        materials_df,
        pd.DataFrame(rating_rows),
        pd.DataFrame(behavior_rows),
        pd.DataFrame(feedback_rows),
    )


# ---------------------------------------------------------------------------
# 从数据库读取
# ---------------------------------------------------------------------------

def load_from_db():
    import pymysql  # 只在需要连库时导入

    cfg = {
        "host": os.getenv("DB_HOST", "localhost"),
        "port": int(os.getenv("DB_PORT", "3306")),
        "user": os.getenv("DB_USERNAME", "root"),
        "password": os.getenv("DB_PASSWORD", ""),
        "database": os.getenv("DB_NAME", "recommend_db"),
        "charset": "utf8mb4",
    }
    conn = pymysql.connect(**cfg)
    try:
        materials_df = pd.read_sql(
            "SELECT id, title, description, tags FROM materials WHERE status = 'ACTIVE'", conn)
        ratings_df = pd.read_sql("SELECT user_id, material_id, rating FROM user_ratings", conn)
        behaviors_df = pd.read_sql("SELECT user_id, material_id, action FROM user_behaviors", conn)
        feedback_df = pd.read_sql(
            "SELECT user_id, material_id, feedback FROM recommendation_feedback", conn)
    finally:
        conn.close()
    return materials_df, ratings_df, behaviors_df, feedback_df


# ---------------------------------------------------------------------------
# 各算法的推荐结果
# ---------------------------------------------------------------------------

def to_rec_lists(rec_df: pd.DataFrame, top_n: int) -> Dict[int, List[int]]:
    if rec_df is None or rec_df.empty:
        return {}
    out: Dict[int, List[int]] = {}
    for uid, group in rec_df.groupby("user_id"):
        ordered = group.sort_values("score", ascending=False)["material_id"].astype(int).tolist()
        out[int(uid)] = ordered[:top_n]
    return out


def apply_feedback_rerank(blended: pd.DataFrame, feedback_df: pd.DataFrame, top_n: int) -> pd.DataFrame:
    """
    在融合分数上叠加用户反馈，复刻 Java 端 RecommendationService.applyPreference 的规则：
      LIKE    +1.5
      DISLIKE -5.0 并从结果中剔除（若全被剔除则回退为只降权）
    """
    if blended.empty or feedback_df is None or feedback_df.empty:
        return blended

    fb = feedback_df.copy()
    fb["delta"] = np.where(fb["feedback"] == "LIKE", FEEDBACK_LIKE_BONUS,
                           np.where(fb["feedback"] == "DISLIKE", -FEEDBACK_DISLIKE_PENALTY, 0.0))
    delta = fb.groupby(["user_id", "material_id"], as_index=False)["delta"].sum()

    merged = blended.merge(delta, on=["user_id", "material_id"], how="left")
    merged["delta"] = merged["delta"].fillna(0.0)
    merged["score"] = merged["score"] + merged["delta"]

    disliked = set(
        map(tuple, fb[fb["feedback"] == "DISLIKE"][["user_id", "material_id"]].values.tolist())
    )

    picked_rows = []
    for uid, group in merged.groupby("user_id"):
        ranked = group.sort_values("score", ascending=False)
        kept = ranked[[(int(uid), int(m)) not in disliked
                       for m in ranked["material_id"]]]
        if kept.empty:
            kept = ranked  # 回退：全被点踩时不排除
        picked_rows.append(kept.head(top_n))
    if not picked_rows:
        return merged.head(0)
    return pd.concat(picked_rows, ignore_index=True)


def run_models(train_ratings: pd.DataFrame,
               train_behaviors: pd.DataFrame,
               materials_df: pd.DataFrame,
               feedback_df: pd.DataFrame,
               top_n: int) -> Dict[str, pd.DataFrame]:
    """跑全部算法，返回 {模型名: 推荐结果 DataFrame}。"""
    models: Dict[str, pd.DataFrame] = {}

    models["UserCF"] = compute_usercf(train_ratings, top_n_items=top_n)
    models["ItemCF"] = compute_itemcf(train_ratings, top_n_items=top_n)
    models["TF-IDF"] = compute_tfidf(materials_df, train_behaviors, top_n_items=top_n)
    models["SVD"] = compute_svd(train_ratings, top_n_items=top_n)

    # 融合：复用生产脚本的 blend_scores（同权重、同归一化、同抖动）
    blended = blend_scores(
        models["UserCF"], models["ItemCF"], models["TF-IDF"], models["SVD"]
    )
    if not blended.empty:
        models["Hybrid（融合）"] = (
            blended.sort_values("score", ascending=False)
            .groupby("user_id").head(top_n).reset_index(drop=True)
        )
        models["Hybrid+多样"] = merge_recommendations(
            models["UserCF"], models["ItemCF"], models["TF-IDF"], models["SVD"]
        )
        models["Hybrid+反馈"] = apply_feedback_rerank(blended, feedback_df, top_n)

    return models


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="推荐效果离线评测（leave-one-out）")
    parser.add_argument("--source", choices=["db", "synthetic"], default="db")
    parser.add_argument("-n", "--synthetic-users", type=int, default=200,
                        help="合成数据中的用户数（仅 --source synthetic 生效）")
    parser.add_argument("--synthetic-items", type=int, default=40)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--k", default="5,10", help="评测截断位置，逗号分隔，如 5,10")
    parser.add_argument("--top-n", type=int, default=10, help="每个算法返回的条数")
    parser.add_argument("--min-interactions", type=int, default=3,
                        help="交互数少于该值的用户不参与评测")
    parser.add_argument("--out", default="", help="把结果写成 CSV 的路径")
    args = parser.parse_args()

    ks = [int(x) for x in args.k.split(",") if x.strip()]
    top_n = max(args.top_n, max(ks))

    # 1) 数据
    if args.source == "synthetic":
        materials_df, ratings_df, behaviors_df, feedback_df = synthesize(
            args.synthetic_users, args.synthetic_items, args.seed)
        label = f"合成数据（{args.synthetic_users} 用户 / {args.synthetic_items} 资料 / seed={args.seed}）"
    else:
        materials_df, ratings_df, behaviors_df, feedback_df = load_from_db()
        label = "数据库真实数据"

    ratings_all = build_rating_matrix(ratings_df, behaviors_df)
    all_items = set(materials_df["id"].astype(int).tolist())

    ground_truth, holdout = build_ground_truth(
        ratings_df, behaviors_df, args.seed, args.min_interactions)
    if not ground_truth:
        print("没有可用于评测的用户（交互数不足）。可尝试 --min-interactions 2")
        return 1

    # 2) 训练集（剔除留出物品）
    train_ratings = drop_holdout(ratings_all, holdout)
    train_behaviors = drop_holdout(behaviors_df, holdout) if not behaviors_df.empty else behaviors_df

    # 3) 跑模型
    models = run_models(train_ratings, train_behaviors, materials_df, feedback_df, top_n)

    # 4) 指标
    print("=" * 78)
    print(f"推荐效果离线评测（leave-one-out）")
    print(f"数据源：{label}")
    print(f"参与评测用户：{len(ground_truth)}    物品数：{len(all_items)}    Top-N：{top_n}")
    print(f"融合权重：UserCF {WEIGHTS['usercf']} / ItemCF {WEIGHTS['itemcf']} / "
          f"TF-IDF {WEIGHTS['tfidf']} / SVD {WEIGHTS['svd']}")
    print("=" * 78)

    rows = []
    if len(ground_truth) < 20:
        print()
        print(f"⚠️ 参与评测的用户只有 {len(ground_truth)} 个。")
        print("   留一法下每个用户只有 1 个相关物品，样本这么少时指标波动极大")
        print("   （一个用户命中与否就能让 Recall 变化 33%），")
        print("   **不建议把这里的数字直接写进论文**。")
        print("   论文里需要稳定曲线时，用 --source synthetic 生成大规模数据，")
        print("   并在正文中明确标注数据来源为合成数据。")
        print()

    for name, rec_df in models.items():
        rec_lists = to_rec_lists(rec_df, top_n)
        for k in ks:
            m = evaluate(rec_lists, ground_truth, all_items, k)
            m["model"] = name
            m["k"] = k
            rows.append(m)

    result = pd.DataFrame(rows)
    cols = ["model", "k", "users"] + [f"precision@{k}" for k in ks] \
           + [f"recall@{k}" for k in ks] + [f"hitrate@{k}" for k in ks] \
           + [f"ndcg@{k}" for k in ks] + ["coverage"]
    # 不同 k 的记录里，只有对应 k 的指标有值，其余为 NaN
    for k in ks:
        for metric in ("precision", "recall", "hitrate", "ndcg"):
            col = f"{metric}@{k}"
            result.loc[result["k"] != k, col] = np.nan

    # 打印 Markdown 表
    header = ["模型", "K", "用户数", "Precision", "Recall", "HitRate", "NDCG", "覆盖率"]
    print("| " + " | ".join(header) + " |")
    print("|" + "|".join(["---"] * len(header)) + "|")
    for _, r in result.iterrows():
        k = int(r["k"])
        print("| {} | {} | {} | {} | {} | {} | {} | {} |".format(
            r["model"], k, int(r["users"]),
            f"{r[f'precision@{k}']:.4f}",
            f"{r[f'recall@{k}']:.4f}",
            f"{r[f'hitrate@{k}']:.4f}",
            f"{r[f'ndcg@{k}']:.4f}",
            f"{r['coverage']:.4f}",
        ))

    print()
    print("指标说明：")
    print("  Precision@K = 命中的相关物品数 / K；Recall@K = 命中数 / 相关物品总数")
    print("  HitRate@K   = 有命中的用户占比；NDCG@K 越靠前命中得分越高")
    print("  覆盖率      = 被推荐到的物品占全部物品的比例（多样性）")

    if args.source == "synthetic":
        print()
        print("⚠️ 本次为合成数据，数值仅用于验证评测链路与相对趋势，")
        print("   不能当作真实业务效果写进论文。真实数据请用 --source db。")

    if args.out:
        out_path = args.out
        result.to_csv(out_path, index=False, encoding="utf-8-sig")
        print(f"\n结果已写入：{out_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
