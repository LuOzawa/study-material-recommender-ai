"""
User-based Collaborative Filtering (UserCF)

Computes user similarity via cosine similarity on the user-item rating matrix,
then recommends items that similar users liked but the target user hasn't seen.
"""

import numpy as np
import pandas as pd
from sklearn.metrics.pairwise import cosine_similarity


def compute_usercf(ratings_df: pd.DataFrame, top_k_users: int = 20, top_n_items: int = 10) -> pd.DataFrame:
    """
    Args:
        ratings_df: DataFrame with columns [user_id, material_id, rating]
        top_k_users: number of most similar users to consider
        top_n_items: number of items to recommend per user
    Returns:
        DataFrame with columns [user_id, material_id, score, algorithm]
    """
    if ratings_df.empty:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    # Build user-item matrix (rows=users, cols=items)
    user_item = ratings_df.pivot_table(index="user_id", columns="material_id", values="rating", fill_value=0)

    users = user_item.index.tolist()
    items = user_item.columns.tolist()
    matrix = user_item.values.astype(float)

    # Compute cosine similarity between users
    sim_matrix = cosine_similarity(matrix)  # shape: (n_users, n_users)

    recommendations = []

    for i, user_id in enumerate(users):
        rated_items = set(ratings_df[ratings_df["user_id"] == user_id]["material_id"].tolist())

        # Get top-K similar users (excluding self)
        sim_scores = list(enumerate(sim_matrix[i]))
        sim_scores = [(j, s) for j, s in sim_scores if j != i and s > 0]
        sim_scores.sort(key=lambda x: x[1], reverse=True)
        top_users = sim_scores[:top_k_users]

        # Predict scores for unrated items
        item_scores: dict[int, float] = {}
        sim_sum: dict[int, float] = {}

        for j, sim in top_users:
            neighbor_id = users[j]
            neighbor_ratings = ratings_df[ratings_df["user_id"] == neighbor_id]
            for _, row in neighbor_ratings.iterrows():
                mid = int(row["material_id"])
                if mid not in rated_items:
                    item_scores[mid] = item_scores.get(mid, 0) + sim * float(row["rating"])
                    sim_sum[mid] = sim_sum.get(mid, 0) + abs(sim)

        for mid, score in item_scores.items():
            if sim_sum[mid] > 0:
                normalized = score / sim_sum[mid]
                recommendations.append({
                    "user_id": user_id,
                    "material_id": mid,
                    "score": round(normalized, 6),
                    "algorithm": "USERCF"
                })

    if not recommendations:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    rec_df = pd.DataFrame(recommendations)
    # Keep top-N per user
    rec_df = (rec_df.sort_values("score", ascending=False)
              .groupby("user_id").head(top_n_items)
              .reset_index(drop=True))
    return rec_df
