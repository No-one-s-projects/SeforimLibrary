#!/usr/bin/env python3
"""בדיקה 7 (סעיף 10): דרגת baseProvenance — inferred(1)/declared(2), סדר SOURCE (מוצהר>מוסק>ללא)."""
import argparse
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import open_db, require_columns, die

SNAPSHOT_INFERRED = 13056          # baseProvenance=1
SNAPSHOT_INFERRED_PAIRS = 20       # זוגות ספרים מוסקים

# סוגי הקישור של ה-mirror query (LinkQueries.sq:selectInverseLinksByTargetLineIds).
_MIRROR_TYPES = ("COMMENTARY", "SUPER_COMMENTARY", "TARGUM", "MIDRASH",
                 "PARSHANUT", "DIBUR_HAMATCHIL", "EIN_MISHPAT", "ELUCIDATION")
_MIRROR_TYPES_SQL = "(" + ",".join("'%s'" % t for t in _MIRROR_TYPES) + ")"
_MIRROR_SAMPLE = 25                # ספרי-יעד לדגימת סדר ה-provenance


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
    # book_base_text מאוחסן (bookId=תלוי, baseBookId=בסיס); קישור מוצהר מאוחסן base→dependant
    # (source=בסיס, target=תלוי — ראו ה-swap ב-SefariaLinksImporter). לכן זוג-הקישור הצפוי
    # הוא (baseBookId, bookId): השוואה מכוונת (source,target), לא frozenset — קישור בכיוון
    # הפוך (תלוי→בסיס) חייב להיכשל.
    declared_total = conn.execute(
        "SELECT COUNT(*) AS c FROM link WHERE baseProvenance = 2").fetchone()["c"]
    bbt_link_pairs = {(r["baseBookId"], r["bookId"])
                      for r in conn.execute("SELECT bookId, baseBookId FROM book_base_text")}
    declared_link_pairs = {(r["s"], r["t"]) for r in conn.execute(
        "SELECT DISTINCT sourceBookId AS s, targetBookId AS t FROM link "
        "WHERE baseProvenance = 2")}
    print(f"baseProvenance=2 (מוצהר): {declared_total} קישורים, "
          f"{len(declared_link_pairs)} זוגות ספרים נבדלים")

    if bbt_link_pairs and declared_total == 0:
        die("book_base_text לא-ריק אך אין קישורי baseProvenance=2")
    off = declared_link_pairs - bbt_link_pairs
    if off:
        sample = sorted(off)[:10]
        die(f"{len(off)} זוגות מוצהרים (source,target) בקישורים שאינם ב-book_base_text "
            f"בכיוון base→dependant: {sample}")

    # (ג) סדר SOURCE (סעיף 4.2): שכפול ORDER BY של ה-mirror query — מוצהר>מוסק>ללא.
    _verify_mirror_provenance_order(conn)

    if args.expect_snapshot:
        if inferred_total != SNAPSHOT_INFERRED:
            die(f"snapshot: baseProvenance=1 total={inferred_total} != {SNAPSHOT_INFERRED}")
        if len(inferred_pairs) != SNAPSHOT_INFERRED_PAIRS:
            die(f"snapshot: זוגות מוסקים={len(inferred_pairs)} != {SNAPSHOT_INFERRED_PAIRS}")

    print("PASS: baseProvenance עקבי; סדר SOURCE מוצהר>מוסק>ללא")
    sys.exit(0)


def _verify_mirror_provenance_order(conn):
    # תצוגת ה-SOURCE (סעיף 4.2): לספר-מקור נתון, קישוריו מסודרים ב-ORDER BY של ה-mirror query
    # (LinkQueries.sq:selectInverseLinksByTargetLineIds, ~שורה 201 — הועתק מילה-במילה), מסונן
    # לסוגי ה-COMMENTARY. דוגם את ספרי-המקור עם מירב דרגות ה-provenance ומאמת שבכל אחד רצף
    # ה-provenance לא-עולה. ה-ORDER BY מבטיח non-increasing אריתמטית, ולכן ההגנה נגד PASS
    # ריק היא הדרישה שלפחות ספר-מקור אחד בדגימה חושף בפועל את שלוש הדרגות declared>inferred>none.
    sources = conn.execute(
        "SELECT l.sourceBookId AS sb, COUNT(DISTINCT l.baseProvenance) AS d, COUNT(*) AS c "
        "FROM link l JOIN connection_type ct ON ct.id = l.connectionTypeId "
        f"WHERE ct.name IN {_MIRROR_TYPES_SQL} AND l.sourceBookId != l.targetBookId "
        "GROUP BY l.sourceBookId HAVING d >= 2 "
        "ORDER BY d DESC, c DESC LIMIT ?", (_MIRROR_SAMPLE,)).fetchall()
    if not sources:
        die("אף ספר-מקור אינו נושא קישורים ב-≥2 דרגות provenance — דגימה לא-מייצגת, כשל")

    saw_all_three = False
    for row in sources:
        sb = row["sb"]
        provs = [r["p"] for r in conn.execute(
            "SELECT l.baseProvenance AS p FROM link l "
            "JOIN connection_type ct ON ct.id = l.connectionTypeId "
            "JOIN book b ON b.id = l.targetBookId "
            "JOIN line sl ON sl.id = l.sourceLineId "
            f"WHERE l.sourceBookId = ? AND ct.name IN {_MIRROR_TYPES_SQL} "
            "AND l.sourceBookId != l.targetBookId "
            "ORDER BY l.baseProvenance DESC, b.isBaseBook DESC, b.orderIndex, sl.lineIndex",
            (sb,))]
        if provs != sorted(provs, reverse=True):
            die(f"סדר SOURCE של ספר {sb} אינו יורד ב-baseProvenance: {provs[:20]}")
        if {0, 1, 2} <= set(provs):
            saw_all_three = True
    if not saw_all_three:
        die("אף ספר-מקור בדגימה אינו מכיל את שלוש הדרגות (declared>inferred>none) — "
            "דגימה לא-מייצגת, כשל למניעת PASS ריק")
    print(f"  סדר SOURCE תקין על {len(sources)} ספרי-מקור; דגימה מייצגת (מוצהר>מוסק>ללא נצפו)")


if __name__ == "__main__":
    main()
