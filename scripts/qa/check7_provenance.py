#!/usr/bin/env python3
"""בדיקה 7 (סעיף 10): דרגת baseProvenance — inferred(1)/declared(2), סדר SOURCE (מוצהר>מוסק>ללא)."""
import argparse
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import open_db, require_columns, die

SNAPSHOT_INFERRED = 13056          # baseProvenance=1
SNAPSHOT_INFERRED_PAIRS = 20       # זוגות ספרים מוסקים


def main():
    ap = argparse.ArgumentParser(description="בדיקה 7: baseProvenance")
    ap.add_argument("--db", required=True)
    ap.add_argument("--expect-snapshot", action="store_true")
    args = ap.parse_args()

    conn = open_db(args.db)
    require_columns(conn, "link",
                    ["sourceBookId", "targetBookId", "sourceLineId",
                     "connectionTypeId", "baseProvenance"])
    require_columns(conn, "book_base_text", ["bookId", "baseBookId"])

    # (א) baseProvenance=1 (INFERRED_TITLE): ספירה, פילוח לפי סוג, זוגות מוסקים, הזוג הגדול.
    inferred_total = conn.execute(
        "SELECT COUNT(*) AS c FROM link WHERE baseProvenance = 1").fetchone()["c"]
    by_type = conn.execute(
        "SELECT ct.name AS n, COUNT(*) AS c FROM link l "
        "JOIN connection_type ct ON ct.id = l.connectionTypeId "
        "WHERE l.baseProvenance = 1 GROUP BY ct.name ORDER BY c DESC").fetchall()
    inferred_pairs = conn.execute(
        "SELECT sourceBookId AS s, targetBookId AS t, COUNT(*) AS c FROM link "
        "WHERE baseProvenance = 1 GROUP BY sourceBookId, targetBookId "
        "ORDER BY c DESC").fetchall()

    print(f"baseProvenance=1 (מוסק): {inferred_total} קישורים")
    print(f"  פילוח לפי סוג: {[(r['n'], r['c']) for r in by_type]}")
    print(f"  זוגות ספרים מוסקים נבדלים: {len(inferred_pairs)}")
    if inferred_pairs:
        big = inferred_pairs[0]
        print(f"  הזוג הגדול: source={big['s']} target={big['t']} ({big['c']} קישורים)")
    print(f"reference snapshot: {SNAPSHOT_INFERRED} קישורים, {SNAPSHOT_INFERRED_PAIRS} זוגות")

    # (ב) baseProvenance=2 (SEFARIA_DECLARED): לא-ריק ועקבי עם book_base_text.
    declared_total = conn.execute(
        "SELECT COUNT(*) AS c FROM link WHERE baseProvenance = 2").fetchone()["c"]
    bbt_pairs = {frozenset((r["bookId"], r["baseBookId"]))
                 for r in conn.execute("SELECT bookId, baseBookId FROM book_base_text")}
    declared_link_pairs = {frozenset((r["s"], r["t"])) for r in conn.execute(
        "SELECT DISTINCT sourceBookId AS s, targetBookId AS t FROM link "
        "WHERE baseProvenance = 2")}
    print(f"baseProvenance=2 (מוצהר): {declared_total} קישורים, "
          f"{len(declared_link_pairs)} זוגות ספרים נבדלים")

    if bbt_pairs and declared_total == 0:
        die("book_base_text לא-ריק אך אין קישורי baseProvenance=2")
    off = declared_link_pairs - bbt_pairs
    if off:
        sample = [tuple(sorted(p)) for p in list(off)[:10]]
        die(f"{len(off)} זוגות מוצהרים בקישורים שאינם ב-book_base_text: {sample}")

    # (ג) סדר SOURCE (סעיף 4.2): baseProvenance DESC ראשון בסדר — מוצהר>מוסק>ללא.
    _verify_source_order(conn)

    if args.expect_snapshot:
        if inferred_total != SNAPSHOT_INFERRED:
            die(f"snapshot: baseProvenance=1 total={inferred_total} != {SNAPSHOT_INFERRED}")
        if len(inferred_pairs) != SNAPSHOT_INFERRED_PAIRS:
            die(f"snapshot: זוגות מוסקים={len(inferred_pairs)} != {SNAPSHOT_INFERRED_PAIRS}")

    print("PASS: baseProvenance עקבי; סדר SOURCE מוצהר>מוסק>ללא")
    sys.exit(0)


def _verify_source_order(conn):
    # בוחר את ספר-המקור עם מירב דרגות ה-provenance ומריץ את ORDER BY של ה-mirror (LinkQueries.sq).
    row = conn.execute(
        "SELECT sourceBookId AS s, COUNT(DISTINCT baseProvenance) AS d, COUNT(*) AS c "
        "FROM link GROUP BY sourceBookId ORDER BY d DESC, c DESC LIMIT 1").fetchone()
    if row is None:
        print("  (אין קישורים לבדיקת סדר SOURCE)")
        return
    src = row["s"]
    provs = [r["p"] for r in conn.execute(
        "SELECT l.baseProvenance AS p FROM link l "
        "JOIN book b ON b.id = l.targetBookId "
        "JOIN line sl ON sl.id = l.sourceLineId "
        "WHERE l.sourceBookId = ? "
        "ORDER BY l.baseProvenance DESC, b.isBaseBook DESC, b.orderIndex, sl.lineIndex",
        (src,))]
    if provs != sorted(provs, reverse=True):
        die(f"סדר SOURCE של ספר {src} אינו יורד ב-baseProvenance: {provs[:20]}")
    print(f"  סדר SOURCE של ספר {src} תקין (דרגות: {sorted(set(provs), reverse=True)})")


if __name__ == "__main__":
    main()
