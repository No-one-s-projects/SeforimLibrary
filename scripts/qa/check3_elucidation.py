#!/usr/bin/env python3
"""בדיקה 3 (סעיף 10): 0 קישורי ELUCIDATION ב-DB (עין אי"ה ב-blacklist)."""
import argparse
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import open_db, require_columns, die

SNAPSHOT_COUNT = 0


def main():
    ap = argparse.ArgumentParser(description="בדיקה 3: ELUCIDATION")
    ap.add_argument("--db", required=True)
    args = ap.parse_args()

    conn = open_db(args.db)
    require_columns(conn, "connection_type", ["id", "name"])
    require_columns(conn, "link", ["connectionTypeId"])

    row = conn.execute("SELECT id FROM connection_type WHERE name = 'ELUCIDATION'").fetchone()
    if row is None:
        die("connection_type 'ELUCIDATION' לא קיים (enum סכמה 2 חסר)")
    ct_id = row["id"]
    count = conn.execute(
        "SELECT COUNT(*) AS c FROM link WHERE connectionTypeId = ?", (ct_id,)).fetchone()["c"]

    print(f"ELUCIDATION connectionTypeId = {ct_id}; קישורים ב-DB = {count}")
    print(f"reference snapshot = {SNAPSHOT_COUNT} (עין אי\"ה ב-books_blacklist)")

    if count != SNAPSHOT_COUNT:
        die(f"צפי 0 קישורי ELUCIDATION, נמצאו {count} "
            "(האם עין אי\"ה הוסר מה-blacklist?)")

    print("PASS: 0 קישורי ELUCIDATION ב-DB")
    sys.exit(0)


if __name__ == "__main__":
    main()
