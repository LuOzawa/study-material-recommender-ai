"""
融合权重网格搜索（validation / test 双留出）。

协议
----
1. 每个用户从正反馈中留出 2 个不同物品：一个只用于选权重（validation），
   另一个只用于最终报告（test）；两个物品都不进入训练集。
2. 四个基模型只训练一次，避免对每个权重组合重复训练。
3. 在 0.1 步长的单纯形上枚举 286 组权重；用 validation NDCG@10 选最优，
   并列时看 validation Recall@10，再并列时取与 baseline 的 L1 距离最小者。
4. 只有 test NDCG@10 比 baseline 至少高 0.005、且 test Recall@10 不低 0.005 时，
   才建议替换生产权重；否则保留 baseline。

用法
----
    python tune_weights.py --source db --step 0.1
    python tune_weights.py --source synthetic -n 200 --step 0.1

依赖 pandas / numpy / scikit-learn（见 requirements.txt）。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

import numpy as np
import pandas as pd

from evaluate_offline import (
    apply_feedback_rerank,
    build_rating_matrix,
    collect_positive_items,
    drop_holdout,
    load_from_db,
    synthesize,
    to_rec_lists,
)
from itemcf import compute_itemcf
from metrics import evaluate
from run_recommendation import WEIGHTS, blend_scores
from svd_recommend import compute_svd
from tfidf_recommend import compute_tfidf
from usercf import compute_usercf

METRIC_K = 10


def parse_weights(text: str) -> Dict[str, float]:
    values = [float(x) for x in text.split(",") if x.strip()]
    if len(values) != 4 or any(v < 0 for v in values) or sum(values) <= 0:
        raise ValueError("权重需要四个非负数，例如 0.3,0.3,0.2,0.2")
    return dict(zip(("usercf", "itemcf", "tfidf", "svd"), values))


def build_two_way_holdout(ratings_df: pd.DataFrame,
                          behaviors_df: pd.DataFrame,
                          min_positive: int,
                          seed: int) -> Tuple[Dict[int, List[int]], Dict[int, List[int]], Dict[int, set], int]:
    """返回 (validation_truth, test_truth, holdout_items, skipped_users)。"""
    positive = collect_positive_items(ratings_df, behaviors_df)
    rng = np.random.default_rng(seed)
    validation: Dict[int, List[int]] = {}
    test: Dict[int, List[int]] = {}
    holdout: Dict[int, set] = {}

    skipped = 0
    for uid in sorted(positive):
        items = sorted(int(x) for x in positive[uid])
        if len(items) < min_positive:
            skipped += 1
            continue
        picked = rng.choice(items, size=2, replace=False)
        validation[int(uid)] = [int(picked[0])]
        test[int(uid)] = [int(picked[1])]
        holdout[int(uid)] = {int(picked[0]), int(picked[1])}
    return validation, test, holdout, skipped


def weight_grid(step: float) -> Iterable[Dict[str, float]]:
    n = int(round(1.0 / step))
    if n <= 0 or abs(n * step - 1.0) > 1e-9:
        raise ValueError("step 必须能整除 1，例如 0.1 或 0.05")
    for a in range(n + 1):
        for b in range(n - a + 1):
            for c in range(n - a - b + 1):
                d = n - a - b - c
                yield {
                    "usercf": a / n,
                    "itemcf": b / n,
                    "tfidf": c / n,
                    "svd": d / n,
                }


def _metric_row(weights: Dict[str, float],
                models: Sequence[pd.DataFrame],
                validation: Dict[int, List[int]],
                test: Dict[int, List[int]],
                all_items: Iterable[int],
                feedback_df: pd.DataFrame,
                top_n: int) -> Dict[str, object]:
    blended = blend_scores(*models, weights=weights)
    val_lists = to_rec_lists(blended, top_n)
    test_lists = to_rec_lists(blended, top_n)
    val_m = evaluate(val_lists, validation, all_items, METRIC_K)
    test_m = evaluate(test_lists, test, all_items, METRIC_K)
    feedback_lists = to_rec_lists(
        apply_feedback_rerank(blended, feedback_df, top_n), top_n)
    feedback_m = evaluate(feedback_lists, test, all_items, METRIC_K)

    row: Dict[str, object] = {
        "w_usercf": round(weights["usercf"], 4),
        "w_itemcf": round(weights["itemcf"], 4),
        "w_tfidf": round(weights["tfidf"], 4),
        "w_svd": round(weights["svd"], 4),
        "val_users": val_m["users"],
        "test_users": test_m["users"],
        "val_precision@10": val_m["precision@10"],
        "val_recall@10": val_m["recall@10"],
        "val_hitrate@10": val_m["hitrate@10"],
        "val_ndcg@10": val_m["ndcg@10"],
        "test_precision@10": test_m["precision@10"],
        "test_recall@10": test_m["recall@10"],
        "test_hitrate@10": test_m["hitrate@10"],
        "test_ndcg@10": test_m["ndcg@10"],
        "test_feedback_ndcg@10": feedback_m["ndcg@10"],
    }
    return row


def _l1(a: Dict[str, float], b: Dict[str, float]) -> float:
    return sum(abs(a[k] - b[k]) for k in ("usercf", "itemcf", "tfidf", "svd"))


def select_best(rows: Sequence[Dict[str, object]],
                baseline: Dict[str, float]) -> Dict[str, object]:
    """Validation NDCG 优先，Recall 次之，最后优先接近 baseline。"""
    return max(
        rows,
        key=lambda r: (
            float(r["val_ndcg@10"]),
            float(r["val_recall@10"]),
            -_l1({k: float(r[f"w_{k}"]) for k in ("usercf", "itemcf", "tfidf", "svd")},
                 baseline),
        ),
    )


def _load_data(args):
    if args.source == "synthetic":
        materials, ratings, behaviors, feedback = synthesize(
            args.synthetic_users, args.synthetic_items, args.seed)
        label = (f"合成数据（{args.synthetic_users} 用户 / "
                 f"{args.synthetic_items} 资料 / seed={args.seed}）")
    else:
        materials, ratings, behaviors, feedback = load_from_db()
        label = "数据库 4 个真实用户 + 200 个模拟用户"
    return materials, ratings, behaviors, feedback, label


def main() -> int:
    parser = argparse.ArgumentParser(description="融合权重网格搜索（双留出）")
    parser.add_argument("--source", choices=["db", "synthetic"], default="db")
    parser.add_argument("-n", "--synthetic-users", type=int, default=200)
    parser.add_argument("--synthetic-items", type=int, default=40)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--step", type=float, default=0.1)
    parser.add_argument("--top-n", type=int, default=10)
    parser.add_argument("--min-positive", type=int, default=3)
    parser.add_argument("--baseline", default="0.3,0.3,0.2,0.2")
    experiments_dir = Path(__file__).resolve().parent / "experiments"
    parser.add_argument("--out", default=str(experiments_dir / "tuning_results.csv"))
    parser.add_argument("--summary", default=str(experiments_dir / "tuning_summary.json"))
    args = parser.parse_args()

    materials, ratings, behaviors, feedback, label = _load_data(args)
    validation, test, holdout, skipped = build_two_way_holdout(
        ratings, behaviors, args.min_positive, args.seed)
    if not validation:
        print("没有用户同时拥有足够的正反馈，无法调参。")
        return 1

    ratings_all = build_rating_matrix(ratings, behaviors)
    train_ratings = drop_holdout(ratings_all, holdout)
    train_behaviors = (drop_holdout(behaviors, holdout)
                       if not behaviors.empty else behaviors)
    all_items = set(materials["id"].astype(int).tolist())

    models = [
        compute_usercf(train_ratings, top_n_items=args.top_n),
        compute_itemcf(train_ratings, top_n_items=args.top_n),
        compute_tfidf(materials, train_behaviors, top_n_items=args.top_n),
        compute_svd(train_ratings, top_n_items=args.top_n),
    ]
    baseline = parse_weights(args.baseline)

    rows: List[Dict[str, object]] = []
    for weights in weight_grid(args.step):
        row = _metric_row(weights, models, validation, test, all_items, feedback, args.top_n)
        row["is_baseline"] = all(abs(weights[k] - baseline[k]) < 1e-9 for k in baseline)
        rows.append(row)

    baseline_row = next(r for r in rows if r["is_baseline"])
    selected = select_best(rows, baseline)
    l1 = _l1({k: float(selected[f"w_{k}"]) for k in baseline}, baseline)
    adopted = (
        float(selected["test_ndcg@10"]) >= float(baseline_row["test_ndcg@10"]) + 0.005
        and float(selected["test_recall@10"]) >= float(baseline_row["test_recall@10"]) - 0.005
    )

    rows.sort(key=lambda r: (float(r["val_ndcg@10"]), float(r["val_recall@10"])), reverse=True)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "w_usercf", "w_itemcf", "w_tfidf", "w_svd", "is_baseline",
        "val_users", "test_users",
        "val_precision@10", "val_recall@10", "val_hitrate@10", "val_ndcg@10",
        "test_precision@10", "test_recall@10", "test_hitrate@10", "test_ndcg@10",
        "test_feedback_ndcg@10",
    ]
    with out_path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    summary = {
        "dataset": label,
        "users": len(validation),
        "skipped_users": skipped,
        "step": args.step,
        "grid_size": len(rows),
        "metric": "validation ndcg@10, tie validation recall@10, tie L1 to baseline",
        "adoption_rule": "test ndcg@10 >= baseline + 0.005 and test recall@10 >= baseline - 0.005",
        "baseline": {k: baseline_row[k] for k in fieldnames},
        "selected": {k: selected[k] for k in fieldnames},
        "selected_l1_to_baseline": round(l1, 6),
        "adopted": adopted,
        "top10": [{k: r[k] for k in fieldnames} for r in rows[:10]],
    }
    summary_path = Path(args.summary)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                            encoding="utf-8")

    print("=" * 96)
    print(f"融合权重网格搜索：{label}")
    print(f"参与调参用户：{len(validation)}    跳过（正反馈<{args.min_positive}）：{skipped}    "
          f"网格：{len(rows)} 组")
    print(f"baseline：{baseline}    val NDCG@10={baseline_row['val_ndcg@10']:.4f}    "
          f"test NDCG@10={baseline_row['test_ndcg@10']:.4f}    "
          f"test Recall@10={baseline_row['test_recall@10']:.4f}")
    print(f"selected：{ {k: selected[f'w_{k}'] for k in baseline} }    "
          f"val NDCG@10={selected['val_ndcg@10']:.4f}    "
          f"test NDCG@10={selected['test_ndcg@10']:.4f}    "
          f"test Recall@10={selected['test_recall@10']:.4f}")
    print(f"是否采用：{'YES' if adopted else 'NO（保留 baseline）'}    L1={l1:.4f}")
    print("-" * 96)
    print("| usercf | itemcf | tfidf | svd | val NDCG@10 | val Recall@10 | test NDCG@10 |")
    print("|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows[:10]:
        print(f"| {r['w_usercf']:.2f} | {r['w_itemcf']:.2f} | {r['w_tfidf']:.2f} | "
              f"{r['w_svd']:.2f} | {r['val_ndcg@10']:.4f} | {r['val_recall@10']:.4f} | "
              f"{r['test_ndcg@10']:.4f} |")
    print(f"\nCSV: {out_path}\nJSON: {summary_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
