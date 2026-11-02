"""Unit tests for the evaluation data assembly (needs pandas/numpy)."""

from __future__ import annotations

import unittest

import pandas as pd

from evaluate_offline import (
    apply_feedback_rerank,
    build_ground_truth,
    build_rating_matrix,
    drop_holdout,
    to_rec_lists,
)
from run_recommendation import blend_scores
from tune_weights import build_two_way_holdout, select_best


class EvaluateAssemblyTest(unittest.TestCase):
    def test_build_rating_matrix_prefers_explicit_rating(self) -> None:
        ratings = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "rating": 5},
        ])
        behaviors = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "action": "VIEW"},
            {"user_id": 2, "material_id": 2, "action": "LIKE"},
            {"user_id": 2, "material_id": 3, "action": "DISLIKE"},
        ])

        merged = build_rating_matrix(ratings, behaviors).set_index(["user_id", "material_id"])

        self.assertEqual(int(merged.loc[(1, 1), "rating"]), 5)
        self.assertEqual(int(merged.loc[(2, 2), "rating"]), 4)
        self.assertEqual(int(merged.loc[(2, 3), "rating"]), 1)

    def test_build_ground_truth_skips_users_with_too_few_positive_items(self) -> None:
        ratings = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "rating": 5},
            {"user_id": 1, "material_id": 2, "rating": 4},
            {"user_id": 1, "material_id": 3, "rating": 2},
            {"user_id": 2, "material_id": 4, "rating": 3},
        ])
        behaviors = pd.DataFrame([
            {"user_id": 1, "material_id": 5, "action": "LIKE"},
            {"user_id": 3, "material_id": 6, "action": "DISLIKE"},
        ])

        ground_truth, holdout = build_ground_truth(ratings, behaviors, seed=7, min_interactions=2)

        self.assertEqual(set(ground_truth), {1})
        self.assertEqual(len(ground_truth[1]), 1)
        self.assertIn(ground_truth[1][0], {1, 2, 5})
        self.assertEqual(holdout[1], set(ground_truth[1]))

    def test_drop_holdout_removes_held_item_only(self) -> None:
        df = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "rating": 5},
            {"user_id": 1, "material_id": 2, "rating": 4},
            {"user_id": 2, "material_id": 2, "rating": 3},
        ])

        filtered = drop_holdout(df, {1: {2}})

        self.assertEqual(
            filtered[["user_id", "material_id"]].values.tolist(),
            [[1, 1], [2, 2]],
        )

    def test_to_rec_lists_sorts_and_truncates(self) -> None:
        rec_df = pd.DataFrame([
            {"user_id": 1, "material_id": 2, "score": 0.2},
            {"user_id": 1, "material_id": 1, "score": 0.9},
            {"user_id": 1, "material_id": 3, "score": 0.5},
            {"user_id": 2, "material_id": 4, "score": 0.1},
        ])

        result = to_rec_lists(rec_df, top_n=2)

        self.assertEqual(result, {1: [1, 3], 2: [4]})

    def test_feedback_rerank_boosts_like_and_drops_dislike(self) -> None:
        blended = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "score": 5.0},
            {"user_id": 1, "material_id": 2, "score": 4.0},
            {"user_id": 1, "material_id": 3, "score": 3.0},
        ])
        feedback = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "feedback": "DISLIKE"},
            {"user_id": 1, "material_id": 2, "feedback": "LIKE"},
        ])

        result = apply_feedback_rerank(blended, feedback, top_n=2)

        self.assertEqual(result["material_id"].tolist(), [2, 3])

    def test_feedback_rerank_falls_back_when_everything_disliked(self) -> None:
        blended = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "score": 1.0},
            {"user_id": 1, "material_id": 2, "score": 2.0},
        ])
        feedback = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "feedback": "DISLIKE"},
            {"user_id": 1, "material_id": 2, "feedback": "DISLIKE"},
        ])

        result = apply_feedback_rerank(blended, feedback, top_n=2)

        self.assertEqual(len(result), 2)
        self.assertTrue((result["score"] < 0).all())


    def test_blend_scores_accepts_explicit_weights(self) -> None:
        usercf = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "score": 1.0, "algorithm": "USERCF"},
            {"user_id": 1, "material_id": 2, "score": 5.0, "algorithm": "USERCF"},
        ])
        itemcf = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "score": 5.0, "algorithm": "ITEMCF"},
            {"user_id": 1, "material_id": 2, "score": 1.0, "algorithm": "ITEMCF"},
        ])
        user_only = blend_scores(
            usercf, itemcf,
            weights={"usercf": 2.0, "itemcf": 0.0, "tfidf": 0.0, "svd": 0.0})
        item_only = blend_scores(
            usercf, itemcf,
            weights={"usercf": 0.0, "itemcf": 1.0, "tfidf": 0.0, "svd": 0.0})

        user_scores = user_only.set_index("material_id")["score"]
        item_scores = item_only.set_index("material_id")["score"]
        self.assertGreater(user_scores[2], user_scores[1])
        self.assertGreater(item_scores[1], item_scores[2])

    def test_two_way_holdout_keeps_validation_and_test_distinct(self) -> None:
        ratings = pd.DataFrame([
            {"user_id": 1, "material_id": 1, "rating": 5},
            {"user_id": 1, "material_id": 2, "rating": 4},
            {"user_id": 1, "material_id": 3, "rating": 3},
            {"user_id": 2, "material_id": 4, "rating": 4},
            {"user_id": 2, "material_id": 5, "rating": 5},
        ])
        behaviors = pd.DataFrame(columns=["user_id", "material_id", "action"])

        val, test, holdout, skipped = build_two_way_holdout(
            ratings, behaviors, min_positive=3, seed=7)
        val2, test2, holdout2, skipped2 = build_two_way_holdout(
            ratings, behaviors, min_positive=3, seed=7)

        self.assertEqual(set(val), {1})
        self.assertEqual(skipped, 1)
        self.assertNotEqual(val[1], test[1])
        self.assertEqual(holdout[1], {val[1][0], test[1][0]})
        self.assertEqual((val, test, holdout, skipped),
                         (val2, test2, holdout2, skipped2))

    def test_select_best_uses_validation_then_recall(self) -> None:
        baseline = {"usercf": 0.3, "itemcf": 0.3, "tfidf": 0.2, "svd": 0.2}
        rows = [
            {"w_usercf": 0.3, "w_itemcf": 0.3, "w_tfidf": 0.2, "w_svd": 0.2,
             "val_ndcg@10": 0.50, "val_recall@10": 0.80},
            {"w_usercf": 0.4, "w_itemcf": 0.3, "w_tfidf": 0.2, "w_svd": 0.1,
             "val_ndcg@10": 0.52, "val_recall@10": 0.78},
            {"w_usercf": 0.2, "w_itemcf": 0.3, "w_tfidf": 0.3, "w_svd": 0.2,
             "val_ndcg@10": 0.52, "val_recall@10": 0.82},
        ]

        best = select_best(rows, baseline)

        self.assertEqual(best["w_usercf"], 0.2)


if __name__ == "__main__":
    unittest.main()
