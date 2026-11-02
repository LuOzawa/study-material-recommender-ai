"""
TF-IDF Content-Based Recommendation

Builds TF-IDF vectors from material title + description + tags,
then recommends materials similar to those the user has interacted with.
"""

import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity


def compute_tfidf(materials_df: pd.DataFrame, behaviors_df: pd.DataFrame,
                  top_n_items: int = 10) -> pd.DataFrame:
    """
    Args:
        materials_df: DataFrame with columns [id, title, description, tags]
        behaviors_df: DataFrame with columns [user_id, material_id, action]
                      actions: VIEW, LIKE, COLLECT, RATE, DOWNLOAD
        top_n_items: number of items to recommend per user
    Returns:
        DataFrame with columns [user_id, material_id, score, algorithm]
    """
    if materials_df.empty or behaviors_df.empty:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    # Build text corpus
    materials_df = materials_df.copy()
    materials_df["text"] = (
        materials_df["title"].fillna("") + " " +
        materials_df["description"].fillna("") + " " +
        materials_df["tags"].fillna("")
    )

    vectorizer = TfidfVectorizer(max_features=5000, stop_words=None)
    tfidf_matrix = vectorizer.fit_transform(materials_df["text"])

    material_ids = materials_df["id"].tolist()
    material_index = {mid: idx for idx, mid in enumerate(material_ids)}

    # Action weights for scoring user interest
    action_weights = {"VIEW": 1.0, "LIKE": 3.0, "COLLECT": 3.0, "RATE": 2.0, "DOWNLOAD": 2.5, "DISLIKE": -2.0}

    recommendations = []

    for user_id in behaviors_df["user_id"].unique():
        user_behaviors = behaviors_df[behaviors_df["user_id"] == user_id]
        interacted_ids = set(user_behaviors["material_id"].tolist())

        # Build weighted user profile vector
        user_vector = None
        total_weight = 0.0

        for _, row in user_behaviors.iterrows():
            mid = int(row["material_id"])
            action = str(row["action"])
            weight = action_weights.get(action, 1.0)

            if mid in material_index:
                vec = tfidf_matrix[material_index[mid]]
                if user_vector is None:
                    user_vector = vec.toarray() * weight
                else:
                    user_vector += vec.toarray() * weight
                total_weight += abs(weight)

        if user_vector is None or total_weight == 0:
            continue

        user_vector = user_vector / total_weight

        # Compute similarity to all materials
        sims = cosine_similarity(user_vector, tfidf_matrix)[0]

        for idx, score in enumerate(sims):
            mid = material_ids[idx]
            if mid not in interacted_ids and score > 0:
                recommendations.append({
                    "user_id": user_id,
                    "material_id": mid,
                    "score": round(float(score), 6),
                    "algorithm": "TFIDF"
                })

    if not recommendations:
        return pd.DataFrame(columns=["user_id", "material_id", "score", "algorithm"])

    rec_df = pd.DataFrame(recommendations)
    rec_df = (rec_df.sort_values("score", ascending=False)
              .groupby("user_id").head(top_n_items)
              .reset_index(drop=True))
    return rec_df
