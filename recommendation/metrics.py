"""
推荐效果评测指标（纯函数，便于单测）。

采用学术界最常用的 Top-N 评测口径：

- 每个被测用户留出一个"相关物品"（leave-one-out），
  看它是否出现在推荐列表的前 K 位。
- Precision@K = 命中数 / K
- Recall@K    = 命中数 / 相关物品总数
- HitRate@K   = 有命中的用户数 / 被测用户数
- NDCG@K      = DCG@K / IDCG@K（越靠前命中得分越高）
- Coverage    = 被推荐过的物品数 / 物品总数（衡量推荐多样性）

因为留一法下每个用户只有 1 个相关物品，所以
Precision@K = (命中 ? 1 : 0) / K，Recall@K = 命中 ? 1 : 0。
"""

from __future__ import annotations

import math
from typing import Dict, Iterable, List, Sequence


def precision_at_k(recommended: Sequence, relevant: Iterable, k: int) -> float:
    if k <= 0:
        return 0.0
    relevant = set(relevant)
    if not relevant:
        return 0.0
    top_k = list(recommended)[:k]
    hits = sum(1 for item in top_k if item in relevant)
    return hits / k


def recall_at_k(recommended: Sequence, relevant: Iterable, k: int) -> float:
    relevant = set(relevant)
    if not relevant:
        return 0.0
    top_k = list(recommended)[:k]
    hits = sum(1 for item in top_k if item in relevant)
    return hits / len(relevant)


def hit_rate_at_k(recommended: Sequence, relevant: Iterable, k: int) -> float:
    relevant = set(relevant)
    if not relevant:
        return 0.0
    return 1.0 if any(item in relevant for item in list(recommended)[:k]) else 0.0


def ndcg_at_k(recommended: Sequence, relevant: Iterable, k: int) -> float:
    relevant = set(relevant)
    if not relevant or k <= 0:
        return 0.0
    top_k = list(recommended)[:k]

    dcg = 0.0
    for rank, item in enumerate(top_k, start=1):
        if item in relevant:
            dcg += 1.0 / math.log2(rank + 1)

    # 理想情况：相关物品排在第一位
    idcg = sum(1.0 / math.log2(rank + 1)
               for rank in range(1, min(len(relevant), k) + 1))
    return dcg / idcg if idcg > 0 else 0.0


def coverage(rec_lists: Dict[int, List], all_items: Iterable) -> float:
    """推荐结果覆盖到的物品比例。"""
    all_items = set(all_items)
    if not all_items:
        return 0.0
    recommended = set()
    for items in rec_lists.values():
        recommended.update(items)
    return len(recommended) / len(all_items)


def evaluate(
    rec_lists: Dict[int, Sequence],
    ground_truth: Dict[int, Sequence],
    all_items: Iterable,
    k: int,
) -> Dict[str, float]:
    """
    对一批用户统一计算指标。

    Args:
        rec_lists:     {user_id: [按分数降序排列的 material_id]}
        ground_truth:  {user_id: [留出的相关 material_id]}
        all_items:     全部物品 id（算覆盖率用）
        k:             截断位置

    只统计「既有推荐结果、又有相关物品」的用户，避免把无法推荐的用户算成 0 拉低均值。
    """
    users = [uid for uid in ground_truth
             if ground_truth.get(uid) and rec_lists.get(uid)]

    if not users:
        return {
            "users": 0,
            f"precision@{k}": 0.0,
            f"recall@{k}": 0.0,
            f"hitrate@{k}": 0.0,
            f"ndcg@{k}": 0.0,
            "coverage": 0.0,
        }

    p = sum(precision_at_k(rec_lists[uid], ground_truth[uid], k) for uid in users) / len(users)
    r = sum(recall_at_k(rec_lists[uid], ground_truth[uid], k) for uid in users) / len(users)
    h = sum(hit_rate_at_k(rec_lists[uid], ground_truth[uid], k) for uid in users) / len(users)
    n = sum(ndcg_at_k(rec_lists[uid], ground_truth[uid], k) for uid in users) / len(users)
    c = coverage({uid: rec_lists[uid] for uid in users}, all_items)

    return {
        "users": len(users),
        f"precision@{k}": round(p, 4),
        f"recall@{k}": round(r, 4),
        f"hitrate@{k}": round(h, 4),
        f"ndcg@{k}": round(n, 4),
        "coverage": round(c, 4),
    }
