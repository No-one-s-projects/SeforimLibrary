#!/usr/bin/env python3
"""בדיקה 8 (סעיף 10): PRAGMA quick_check + PRAGMA foreign_key_check. כשל על כל פלט חריג."""
import argparse
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import open_db, die


def main():
    ap = argparse.ArgumentParser(description="בדיקה 8: שלמות DB")
    ap.add_argument("--db", required=True)
    args = ap.parse_args()

    conn = open_db(args.db)

    qc = [r[0] for r in conn.execute("PRAGMA quick_check")]
    if qc != ["ok"]:
        for line in qc[:20]:
            print(f"  quick_check: {line}", file=sys.stderr)
        die(f"PRAGMA quick_check נכשל ({len(qc)} שורות)")
    print("PRAGMA quick_check = ok")

    fk = conn.execute("PRAGMA foreign_key_check").fetchall()
    if fk:
        for row in fk[:20]:
            print(f"  foreign_key_check: {tuple(row)}", file=sys.stderr)
        die(f"PRAGMA foreign_key_check מצא {len(fk)} הפרות FK")
    print("PRAGMA foreign_key_check = נקי")

    print("PASS: שלמות DB תקינה")
    sys.exit(0)


if __name__ == "__main__":
    main()
