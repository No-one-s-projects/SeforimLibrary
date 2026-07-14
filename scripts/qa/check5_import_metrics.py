#!/usr/bin/env python3
"""בדיקה 5 (סעיף 10.5): אימות דו"ח מדדי ייבוא הקישורים של ספריא.

היבואן (SefariaLinksImporter, קומיט 6917895) כותב דו"ח JSON דטרמיניסטי לצד ה-DB —
`<db>.link-import-metrics.json` — בצורה:
    {"perType": {"TYPE": {"rowsRead": N, "dropped": N, "resolvedPairs": N, "written": N}}}

סמנטיקת המפתוח (LinkImportTypeMetrics): ‏rowsRead/dropped/resolvedPairs ממופתחים לפי
הטיפוס הגולמי מה-CSV; ‏written לפי הטיפוס הסופי שנשמר (אחרי שדרוג blank→schema והיפוך
כיוון). לכן written ≤ resolvedPairs תקף רק **גלובלית** (השדרוג מעביר בין סוגים), ואילו
dropped ≤ rowsRead תקף פר-סוג (אותו מפתוח). resolvedPairs יכול לעלות על rowsRead
פר-סוג (שורה אחת ⇒ כמה זוגות fromRefs×toRefs) ולכן אינו נבדק מולו.

אינווריאנטים נאכפים:
  1. פר-סוג: כל ארבעת המונים שלמים ו-≥0.
  2. פר-סוג: dropped ≤ rowsRead.
  3. גלובלית: Σwritten ≤ ΣresolvedPairs.
  4. עם --db (אופציונלי): לכל סוג בדו"ח, ספירת הקישורים מאותו סוג ב-DB ≥ written —
     ה-DB מצטבר קישורים ממקורות נוספים (havrouta/otzaria/linker), לכן ≥ ולא ==;
     דטרמיניסטי ובטוח בלי לדעת אילו סוגים באים רק מ-CSV של ספריא.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import open_db, require_columns, die

REQUIRED_KEYS = ("rowsRead", "dropped", "resolvedPairs", "written")


def load_metrics(path):
    if not os.path.isfile(path):
        die(f"קובץ metrics לא קיים: {path}")
    with open(path, encoding="utf-8") as fh:
        try:
            top = json.load(fh)
        except ValueError as e:
            die(f"metrics אינו JSON תקין: {e}")
    if not isinstance(top, dict) or not isinstance(top.get("perType"), dict):
        die('צורת metrics לא תקינה: נדרש אובייקט עם "perType" (מילון סוג→מונים)')
    per_type = {}
    for name, vals in top["perType"].items():
        if not isinstance(vals, dict):
            die(f"perType[{name!r}] אינו אובייקט")
        missing = [k for k in REQUIRED_KEYS if k not in vals]
        extra = sorted(set(vals) - set(REQUIRED_KEYS))
        if missing or extra:
            die(f"perType[{name!r}]: מפתחות חסרים {missing} / לא-מוכרים {extra}")
        for k in REQUIRED_KEYS:
            v = vals[k]
            if not isinstance(v, int) or isinstance(v, bool):
                die(f"perType[{name!r}].{k} אינו מספר שלם: {v!r}")
        per_type[name] = vals
    if not per_type:
        die("perType ריק — הדו\"ח אינו מכיל אף סוג קישור")
    return per_type


def main():
    ap = argparse.ArgumentParser(description="בדיקה 5: מדדי ייבוא קישורים לפי סוג")
    ap.add_argument("--metrics", required=True,
                    help="נתיב הדו\"ח <db>.link-import-metrics.json שכתב היבואן")
    ap.add_argument("--db",
                    help="אופציונלי: הצלבה מול ה-DB — לכל סוג, קישורים ב-DB ≥ written")
    args = ap.parse_args()

    per_type = load_metrics(args.metrics)

    for name in sorted(per_type):
        m = per_type[name]
        for k in REQUIRED_KEYS:
            if m[k] < 0:
                die(f"{name}.{k} שלילי: {m[k]}")
        if m["dropped"] > m["rowsRead"]:
            die(f"{name}: dropped={m['dropped']} > rowsRead={m['rowsRead']}")

    total_written = sum(m["written"] for m in per_type.values())
    total_resolved = sum(m["resolvedPairs"] for m in per_type.values())
    total_rows = sum(m["rowsRead"] for m in per_type.values())
    total_dropped = sum(m["dropped"] for m in per_type.values())
    print(f"סוגים בדו\"ח: {len(per_type)}; ΣrowsRead={total_rows} Σdropped={total_dropped} "
          f"ΣresolvedPairs={total_resolved} Σwritten={total_written}")
    if total_written > total_resolved:
        die(f"Σwritten={total_written} > ΣresolvedPairs={total_resolved} — "
            "קישור נכתב בלי זוג שנפתר")

    if args.db:
        conn = open_db(args.db)
        require_columns(conn, "link", ["connectionTypeId"])
        require_columns(conn, "connection_type", ["id", "name"])
        db_counts = {r["n"]: r["c"] for r in conn.execute(
            "SELECT ct.name AS n, COUNT(*) AS c FROM link l "
            "JOIN connection_type ct ON ct.id = l.connectionTypeId GROUP BY ct.name")}
        for name in sorted(per_type):
            written = per_type[name]["written"]
            db_c = db_counts.get(name, 0)
            if db_c < written:
                die(f"{name}: קישורים ב-DB ({db_c}) < written בדו\"ח ({written})")
        print(f"הצלבת DB: לכל {len(per_type)} הסוגים, קישורי ה-DB ≥ written")

    print("PASS: דו\"ח מדדי הייבוא עקבי")
    sys.exit(0)


if __name__ == "__main__":
    main()
