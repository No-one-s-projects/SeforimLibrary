#!/usr/bin/env python3
"""בדיקה 5 (סעיף 10.5): אימות דו"ח מדדי ייבוא הקישורים של ספריא.

היבואן (SefariaLinksImporter) כותב דו"ח JSON דטרמיניסטי לצד ה-DB —
`<db>.link-import-metrics.json` — בצורה (מקומיט ddb1f24 ואילך):
    {
      "db_schema_version": "2" | null,   # נקרא מ-schema_meta; null עד שלב ה-stamp
      "db_version": "15" | null,
      "db_size_bytes": 7906639872,
      "insertedByType": {"TYPE": {"rowsRead": N, "dropped": N,
                                  "resolvedPairs": N, "written": N}},
      "persistedByType": {"TYPE": COUNT}
    }

סמנטיקה:
  * insertedByType — מוני שלב-ההכנסה, **טרם** ה-demotion. ‏rowsRead/dropped/
    resolvedPairs ממופתחים לפי הטיפוס הגולמי מה-CSV; ‏written לפי הטיפוס שנשמר
    בהכנסה (אחרי שדרוג blank→schema והיפוך כיוון) — לכן written ≤ resolvedPairs
    תקף רק **גלובלית**, ואילו dropped ≤ rowsRead תקף פר-סוג.
  * persistedByType — ‏COUNT(*) סמכותי **אחרי** demoteCrossCorpusDependantLinks
    (למשל COMMENTARY/MIDRASH חוצי-קורפוס → RELATED). ה-demotion רק ממיין-מחדש,
    ולכן Σ written(insertedByType) == Σ persistedByType (נאכף גם בצד Kotlin).

אינווריאנטים נאכפים:
  1. כל המונים (insertedByType×4 + persistedByType) שלמים ו-≥0.
  2. פר-סוג ב-insertedByType: dropped ≤ rowsRead.
  3. גלובלית: Σwritten ≤ ΣresolvedPairs.
  4. גלובלית: Σwritten(insertedByType) == Σ persistedByType (מדויק).
  5. עם --db: לכל סוג ב-persistedByType, ספירת הקישורים מאותו סוג ב-DB ≥
     persistedByType[type] — ה-DB המלא מצטבר קישורים ממקורות נוספים
     (havrouta/otzaria/linker). עם --sefaria-stage (ה-DB הוא DB בשלב-ספריא
     בלבד) ההשוואה מדויקת (==).
  6. עם --db: אם db_schema_version/db_version בדו"ח אינם null — הצלבה מול
     schema_meta ב-DB (אי-התאמה = כשל).
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import open_db, require_columns, die

INSERTED_KEYS = ("rowsRead", "dropped", "resolvedPairs", "written")


def load_metrics(path):
    if not os.path.isfile(path):
        die(f"קובץ metrics לא קיים: {path}")
    with open(path, encoding="utf-8") as fh:
        try:
            top = json.load(fh)
        except ValueError as e:
            die(f"metrics אינו JSON תקין: {e}")
    if not isinstance(top, dict):
        die("צורת metrics לא תקינה: נדרש אובייקט JSON ברמה העליונה")
    # דחיית הצורה הישנה (perType) בקול — הדו"ח קדם לקומיט ddb1f24.
    if "perType" in top and "insertedByType" not in top:
        die('צורת metrics מיושנת: נמצא "perType" ולא "insertedByType"/"persistedByType" '
            "— הדו\"ח נכתב לפני קומיט ddb1f24. בנה מחדש עם היבואן העדכני.")
    for req in ("insertedByType", "persistedByType"):
        if not isinstance(top.get(req), dict):
            die(f'צורת metrics לא תקינה: נדרש אובייקט "{req}" (מילון סוג→...)')

    inserted = {}
    for name, vals in top["insertedByType"].items():
        if not isinstance(vals, dict):
            die(f"insertedByType[{name!r}] אינו אובייקט")
        missing = [k for k in INSERTED_KEYS if k not in vals]
        extra = sorted(set(vals) - set(INSERTED_KEYS))
        if missing or extra:
            die(f"insertedByType[{name!r}]: מפתחות חסרים {missing} / לא-מוכרים {extra}")
        for k in INSERTED_KEYS:
            v = vals[k]
            if not isinstance(v, int) or isinstance(v, bool):
                die(f"insertedByType[{name!r}].{k} אינו מספר שלם: {v!r}")
        inserted[name] = vals
    if not inserted:
        die("insertedByType ריק — הדו\"ח אינו מכיל אף סוג קישור")

    persisted = {}
    for name, v in top["persistedByType"].items():
        if not isinstance(v, int) or isinstance(v, bool):
            die(f"persistedByType[{name!r}] אינו מספר שלם: {v!r}")
        persisted[name] = v

    schema_version = top.get("db_schema_version")
    db_version = top.get("db_version")
    for label, val in (("db_schema_version", schema_version), ("db_version", db_version)):
        if val is not None and not isinstance(val, str):
            die(f"{label} חייב להיות מחרוזת או null: {val!r}")
    return inserted, persisted, schema_version, db_version


def main():
    ap = argparse.ArgumentParser(description="בדיקה 5: מדדי ייבוא קישורים לפי סוג")
    ap.add_argument("--metrics", required=True,
                    help="נתיב הדו\"ח <db>.link-import-metrics.json שכתב היבואן")
    ap.add_argument("--db",
                    help="אופציונלי: הצלבה מול ה-DB — לכל סוג, קישורים ב-DB ≥ persisted "
                         "(או == עם --sefaria-stage)")
    ap.add_argument("--sefaria-stage", action="store_true",
                    help="ה-DB הוא DB בשלב-ספריא (טרם צבירת havrouta/otzaria/linker); "
                         "הצלבת ה-DB נעשית ב-== במקום ≥")
    args = ap.parse_args()

    inserted, persisted, schema_version, db_version = load_metrics(args.metrics)

    for name in sorted(inserted):
        m = inserted[name]
        for k in INSERTED_KEYS:
            if m[k] < 0:
                die(f"insertedByType.{name}.{k} שלילי: {m[k]}")
        if m["dropped"] > m["rowsRead"]:
            die(f"insertedByType.{name}: dropped={m['dropped']} > rowsRead={m['rowsRead']}")
    for name in sorted(persisted):
        if persisted[name] < 0:
            die(f"persistedByType.{name} שלילי: {persisted[name]}")

    total_written = sum(m["written"] for m in inserted.values())
    total_resolved = sum(m["resolvedPairs"] for m in inserted.values())
    total_rows = sum(m["rowsRead"] for m in inserted.values())
    total_dropped = sum(m["dropped"] for m in inserted.values())
    total_persisted = sum(persisted.values())
    print(f"סוגים: inserted={len(inserted)} persisted={len(persisted)}; "
          f"ΣrowsRead={total_rows} Σdropped={total_dropped} "
          f"ΣresolvedPairs={total_resolved} Σwritten={total_written} "
          f"Σpersisted={total_persisted}")
    if total_written > total_resolved:
        die(f"Σwritten={total_written} > ΣresolvedPairs={total_resolved} — "
            "קישור נכתב בלי זוג שנפתר")
    if total_written != total_persisted:
        die(f"Σwritten(insertedByType)={total_written} != Σpersisted={total_persisted} — "
            "ה-demotion אמור רק למיין-מחדש, לא להוסיף/למחוק (אינווריאנט נאכף גם בצד Kotlin)")

    if args.db:
        conn = open_db(args.db)
        require_columns(conn, "link", ["connectionTypeId"])
        require_columns(conn, "connection_type", ["id", "name"])
        db_counts = {r["n"]: r["c"] for r in conn.execute(
            "SELECT ct.name AS n, COUNT(*) AS c FROM link l "
            "JOIN connection_type ct ON ct.id = l.connectionTypeId GROUP BY ct.name")}
        for name in sorted(persisted):
            want = persisted[name]
            db_c = db_counts.get(name, 0)
            if args.sefaria_stage:
                if db_c != want:
                    die(f"{name}: קישורים ב-DB ({db_c}) != persisted בדו\"ח ({want}) "
                        "[--sefaria-stage: נדרש שוויון]")
            elif db_c < want:
                die(f"{name}: קישורים ב-DB ({db_c}) < persisted בדו\"ח ({want})")
        rel = "==" if args.sefaria_stage else "≥"
        print(f"הצלבת DB: לכל {len(persisted)} הסוגים, קישורי ה-DB {rel} persisted")

        # הצלבת schema_meta כשהערכים בדו"ח אינם null.
        if schema_version is not None or db_version is not None:
            require_columns(conn, "schema_meta", ["key", "value"])
            meta = {r["key"]: r["value"] for r in conn.execute(
                "SELECT key, value FROM schema_meta")}
            for key, reported in (("db_schema_version", schema_version),
                                  ("db_version", db_version)):
                if reported is None:
                    continue
                actual = meta.get(key)
                if actual is None:
                    die(f"schema_meta חסר מפתח {key!r} אך הדו\"ח מדווח {reported!r}")
                if str(actual) != str(reported):
                    die(f"schema_meta[{key}]={actual!r} != בדו\"ח {reported!r}")
            print("הצלבת schema_meta: db_schema_version/db_version תואמים ל-DB")

    print("PASS: דו\"ח מדדי הייבוא עקבי")
    sys.exit(0)


if __name__ == "__main__":
    main()
