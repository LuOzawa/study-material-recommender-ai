"""
Item-based Collaborative Filtering (ItemCF)

Computes item similarity via cosine similarity on the item-user rating matrix,
then recommends items similar to those the target user has rated highly.
"""

import numpy as np
import pandas as pd
from sklearn.metrics.pairwise import cosine_similarity


def compute_itemcf(ratings_df: pd.DataFrame, top_k_items: int = 20, top_n_items: int = 10) -> pd.DataFrame:
    """
    Args:
        ratings_df: DataFrame with columns [user_id, material_id, rating]
        top_k_items: number of most similar items to consider per rated item
        top_n_items: number of items to recommend per user
    Returns:
        DataFrame with columns [user_id, material_id, score, algorithm]
    """
    if ratings_df.empty:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    # Build item-user matrix (rows=items, cols=users)
    item_user = ratings_df.pivot_table(index="material_id", columns="user_id", values="rating", fill_value=0)

    items = item_user.index.tolist()
    matrix = item_user.values.astype(float)

    # Cosine similarity between items
    sim_matrix = cosine_similarity(matrix)  # shape: (n_items, n_items)
    item_index = {item_id: idx for idx, item_id in enumerate(items)}

    recommendations = []

    for user_id in ratings_df["user_id"].unique():
        user_ratings = ratings_df[ratings_df["user_id"] == user_id]
        rated_items = set(user_ratings["material_id"].tolist())

        item_scores: dict[int, float] = {}
        sim_sum: dict[int, float] = {}

        for _, row in user_ratings.iterrows():
            rated_mid = int(row["material_id"])
            user_rating = float(row["rating"])

            if rated_mid not in item_index:
                continue

            i = item_index[rated_mid]
            # Get top-K similar items
            sims = [(items[j], sim_matrix[i][j]) for j in range(len(items)) if items[j] != rated_mid and sim_matrix[i][j] > 0]
            sims.sort(key=lambda x: x[1], reverse=True)
            top_similar = sims[:top_k_items]

            for sim_mid, sim in top_similar:
                if sim_mid not in rated_items:
                    item_scores[sim_mid] = item_scores.get(sim_mid, 0) + sim * user_rating
                    sim_sum[sim_mid] = sim_sum.get(sim_mid, 0) + abs(sim)

        for mid, score in item_scores.items():
            if sim_sum[mid] > 0:
                normalized = score / sim_sum[mid]
                recommendations.append({
                    "user_id": user_id,
                    "material_id": mid,
                    "score": round(normalized, 6),
                    "algorithm": "ITEMCF"
                })

    if not recommendations:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    rec_df = pd.DataFrame(recommendations)
    rec_df = (rec_df.sort_values("score", ascending=False)
              .groupby("user_id").head(top_n_items)
              .reset_index(drop=True))
    return rec_df
