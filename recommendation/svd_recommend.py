"""
Matrix Factorization Recommendation (SVD)

Uses TruncatedSVD on the user-item rating matrix to learn latent factors,
then reconstructs user preference scores for unseen materials.
"""

import numpy as np
import pandas as pd
from sklearn.decomposition import TruncatedSVD


def compute_svd(
    ratings_df: pd.DataFrame,
    n_factors: int = 20,
    top_n_items: int = 10,
) -> pd.DataFrame:
    """
    Args:
        ratings_df: DataFrame with columns [user_id, material_id, rating]
        n_factors: latent dimensions for matrix factorization
        top_n_items: number of items to recommend per user
    Returns:
        DataFrame with columns [user_id, material_id, score, algorithm]
    """
    if ratings_df.empty:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    user_item = ratings_df.pivot_table(
        index="user_id",
        columns="material_id",
        values="rating",
        fill_value=0.0,
    )

    users = user_item.index.tolist()
    items = user_item.columns.tolist()
    matrix = user_item.values.astype(float)
    n_users, n_items = matrix.shape

    # Need at least a 2x2 matrix for SVD factorization.
    if n_users < 2 or n_items < 2:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    nonzero_counts = (matrix > 0).sum(axis=1)
    user_means = np.divide(
        matrix.sum(axis=1),
        nonzero_counts,
        out=np.zeros(n_users, dtype=float),
        where=nonzero_counts != 0,
    )

    centered = matrix - user_means[:, None]
    centered[matrix == 0] = 0

    max_components = min(n_factors, n_users - 1, n_items - 1)
    if max_components < 1:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    svd = TruncatedSVD(n_components=max_components, random_state=42)
    latent_user = svd.fit_transform(centered)
    reconstructed = np.dot(latent_user, svd.components_) + user_means[:, None]

    recommendations = []
    user_rated_map = (
        ratings_df.groupby("user_id")["material_id"]
        .apply(lambda series: set(series.astype(int).tolist()))
        .to_dict()
    )

    for user_idx, user_id in enumerate(users):
        rated_items = user_rated_map.get(int(user_id), set())
        candidates = []
        for item_idx, material_id in enumerate(items):
            mid = int(material_id)
            if mid in rated_items:
                continue
            score = float(reconstructed[user_idx, item_idx])
            candidates.append((mid, score))

        if not candidates:
            continue

        candidates.sort(key=lambda x: x[1], reverse=True)
        for mid, score in candidates[:top_n_items]:
            recommendations.append(
                {
                    "user_id": int(user_id),
                    "material_id": mid,
                    "score": round(score, 6),
                    "algorithm": "SVD",
                }
            )

    if not recommendations:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    rec_df = pd.DataFrame(recommendations)
    rec_df = (
        rec_df.sort_values("score", ascending=False)
        .groupby("user_id")
        .head(top_n_items)
        .reset_index(drop=True)
    )
    return rec_df

