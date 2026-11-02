"""One-off MySQL -> PostgreSQL data migration.

Target schema must already exist (apply sql/schema.sql first). The script
truncates all 12 tables, copies rows while preserving ids, converts MySQL
tinyint flags to booleans, resets identity sequences and verifies counts.

Environment:
    SRC_HOST (127.0.0.1), SRC_PORT (3306), SRC_USER (root),
    SRC_PASSWORD (""), SRC_DB (recommend_db)
    DST_URL  postgresql://postgres:postgres@127.0.0.1:5432/recommend_db
"""

from __future__ import annotations

import argparse
import os
import sys

import pymysql
import psycopg

TABLES = [
    "users",
    "categories",
    "materials",
    "ai_conversations",
    "material_comments",
    "material_likes",
    "recommendation_feedback",
    "recommendations",
    "user_behaviors",
    "user_favorites",
    "user_preferences",
    "user_ratings",
]

BOOL_COLUMNS = {
    ("users", "enabled"),
    ("user_preferences", "auto_refresh"),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--allow-truncate",
        action="store_true",
        help="allow wiping non-empty target tables (required when target has rows)",
    )
    return parser.parse_args()


def target_row_count(conn: psycopg.Connection) -> int:
    total = 0
    for table in TABLES:
        total += int(conn.execute(f"SELECT count(*) FROM {table}").fetchone()[0])
    return total


def main() -> int:
    args = parse_args()

    src_config = {
        "host": os.getenv("SRC_HOST", "127.0.0.1"),
        "port": int(os.getenv("SRC_PORT", "3306")),
        "user": os.getenv("SRC_USER", "root"),
        "password": os.getenv("SRC_PASSWORD", ""),
        "database": os.getenv("SRC_DB", "recommend_db"),
        "charset": "utf8mb4",
    }
    dst_url = os.getenv("DST_URL", "postgresql://postgres:postgres@127.0.0.1:5432/recommend_db")

    source = pymysql.connect(**src_config)
    try:
        with psycopg.connect(dst_url) as target:
            existing = target_row_count(target)
            if existing and not args.allow_truncate:
                print(
                    f"target has {existing} rows; re-run with --allow-truncate to replace them",
                    file=sys.stderr,
                )
                return 2

            with target.transaction():
                target.execute("SET CONSTRAINTS ALL DEFERRED")
                target.execute(
                    "TRUNCATE TABLE " + ", ".join(TABLES) + " RESTART IDENTITY CASCADE"
                )

                with source.cursor() as src_cur, target.cursor() as dst_cur:
                    for table in TABLES:
                        src_cur.execute(f"SELECT * FROM {table}")
                        columns = [item[0] for item in src_cur.description]
                        rows = src_cur.fetchall()
                        converted = [
                            tuple(
                                bool(value) if (table, column) in BOOL_COLUMNS else value
                                for column, value in zip(columns, row)
                            )
                            for row in rows
                        ]
                        placeholders = ", ".join(["%s"] * len(columns))
                        dst_cur.executemany(
                            f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({placeholders})",
                            converted,
                        )
                        print(f"  {table}: {len(converted)} rows")

                    for table in TABLES:
                        dst_cur.execute(
                            f"SELECT setval(pg_get_serial_sequence('{table}', 'id'), "
                            f"COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM {table}"
                        )

            failures: list[str] = []
            with source.cursor() as src_cur:
                for table in TABLES:
                    src_cur.execute(f"SELECT count(*) FROM {table}")
                    expected = int(src_cur.fetchone()[0])
                    actual = int(target.execute(f"SELECT count(*) FROM {table}").fetchone()[0])
                    if expected != actual:
                        failures.append(f"{table}: source={expected} target={actual}")

            if failures:
                print("COUNT MISMATCH", file=sys.stderr)
                for failure in failures:
                    print(f"  {failure}", file=sys.stderr)
                return 1

            print("MIGRATION OK")
            return 0
    finally:
        source.close()


if __name__ == "__main__":
    raise SystemExit(main())
