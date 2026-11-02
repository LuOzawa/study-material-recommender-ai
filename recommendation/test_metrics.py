"""Unit tests for the pure recommendation metrics (stdlib unittest)."""

from __future__ import annotations

import math
import unittest

from metrics import (
    coverage,
    evaluate,
    hit_rate_at_k,
    ndcg_at_k,
    precision_at_k,
    recall_at_k,
)


class MetricsTest(unittest.TestCase):
    def test_precision_counts_hits_over_k(self) -> None:
        self.assertAlmostEqual(precision_at_k([1, 2, 3], {2}, 3), 1 / 3)
        self.assertAlmostEqual(precision_at_k([1, 2, 3], {2}, 1), 0.0)
        self.assertAlmostEqual(precision_at_k([1, 2, 3], {2}, 2), 0.5)

    def test_recall_uses_relevant_set_size(self) -> None:
        self.assertAlmostEqual(recall_at_k([1, 2, 3], {2, 4}, 3), 0.5)
        self.assertAlmostEqual(recall_at_k([1, 2, 3], {4}, 3), 0.0)

    def test_hit_rate_and_empty_inputs(self) -> None:
        self.assertEqual(hit_rate_at_k([1, 2, 3], {2}, 2), 1.0)
        self.assertEqual(hit_rate_at_k([1, 2, 3], {2}, 1), 0.0)
        self.assertEqual(precision_at_k([1], set(), 1), 0.0)
        self.assertEqual(recall_at_k([1], set(), 1), 0.0)
        self.assertEqual(hit_rate_at_k([1], set(), 1), 0.0)
        self.assertEqual(ndcg_at_k([1], set(), 1), 0.0)

    def test_ndcg_rewards_earlier_hits(self) -> None:
        self.assertAlmostEqual(ndcg_at_k([1, 2], {1}, 2), 1.0)
        self.assertAlmostEqual(ndcg_at_k([2, 1], {1}, 2), 1 / math.log2(3))
        self.assertEqual(ndcg_at_k([1, 2], {1}, 0), 0.0)

    def test_coverage(self) -> None:
        self.assertAlmostEqual(coverage({1: [1, 2], 2: [2, 3]}, {1, 2, 3, 4}), 0.75)
        self.assertEqual(coverage({}, {1, 2}), 0.0)
        self.assertEqual(coverage({1: [1]}, set()), 0.0)

    def test_evaluate_aggregates_only_users_with_both_sides(self) -> None:
        result = evaluate(
            rec_lists={1: [1, 2, 3], 2: [4]},
            ground_truth={1: [2], 2: [5], 3: [1]},
            all_items={1, 2, 3, 4, 5},
            k=2,
        )
        self.assertEqual(result["users"], 2)
        self.assertAlmostEqual(result["precision@2"], 0.25)
        self.assertAlmostEqual(result["recall@2"], 0.5)
        self.assertAlmostEqual(result["hitrate@2"], 0.5)
        self.assertAlmostEqual(result["ndcg@2"], 0.3155, places=4)
        self.assertAlmostEqual(result["coverage"], 0.8)

    def test_evaluate_without_eligible_users(self) -> None:
        result = evaluate({}, {1: [1]}, {1}, 5)
        self.assertEqual(result["users"], 0)
        self.assertAlmostEqual(result["precision@5"], 0.0)
        self.assertAlmostEqual(result["coverage"], 0.0)


if __name__ == "__main__":
    unittest.main()
