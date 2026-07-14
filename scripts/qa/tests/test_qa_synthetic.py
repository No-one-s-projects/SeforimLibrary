#!/usr/bin/env python3
"""מטריצת אימות סינתטית (pass+fail) לסקריפטי ה-QA + רגרסיות ממוקדות. stdlib בלבד.

הרצה: python3 scripts/qa/tests/test_qa_synthetic.py  (יציאה 0 = הכול עבר).
כל בדיקה בונה schemas/DB מינימליים ומריצה את הסקריפט האמיתי כתת-תהליך.
"""
import json
import os
import sqlite3
import subprocess
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
_QA = os.path.dirname(_HERE)
sys.path.insert(0, _QA)
import common  # noqa: E402

_FAILURES = []


def _check(name, cond, detail=""):
    status = "PASS" if cond else "FAIL"
    print(f"  [{status}] {name}" + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        _FAILURES.append(name)


def _run(script, *args):
    p = subprocess.run([sys.executable, os.path.join(_QA, script), *args],
                       capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def _write_schema(d, fn, en, he, base_he=None, he_variants=(), en_variants=(),
                  dependence=None, coll_he=None, coll_en=None):
    top = {"schema": {"title": en, "heTitle": he}}
    if base_he:
        top["base_text_titles"] = [{"he": base_he}]
    if he_variants:
        top["heTitleVariants"] = list(he_variants)
    if en_variants:
        top["titleVariants"] = list(en_variants)
    if dependence:
        top["dependence"] = dependence
    if coll_he or coll_en:
        top["collective_title"] = {"he": coll_he, "en": coll_en}
    with open(os.path.join(d, fn), "w", encoding="utf-8") as fh:
        json.dump(top, fh, ensure_ascii=False)


# מזהי source בפיקסטורות: 1=Sefaria, 2=MoreBooks (מקור לא-Sefaria לרגרסיות הסינון).
SRC_SEFARIA, SRC_MOREBOOKS = 1, 2


def _make_db(path, books=(), bbt=(), links=(), conn_types=(), lines=(),
             sources=((SRC_SEFARIA, "Sefaria"), (SRC_MOREBOOKS, "MoreBooks")),
             schema_meta=()):
    # books: 8-tuple — (id, heRef, dep, collHe, collEn, isBaseBook, orderIndex, sourceId).
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE source(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
        CREATE TABLE book(id INTEGER PRIMARY KEY, heRef TEXT, dependenceType TEXT,
            collectiveTitleHe TEXT, collectiveTitleEn TEXT, isBaseBook INTEGER DEFAULT 0,
            orderIndex REAL DEFAULT 999, sourceId INTEGER NOT NULL DEFAULT 1);
        CREATE TABLE book_base_text(bookId INTEGER, baseBookId INTEGER);
        CREATE TABLE connection_type(id INTEGER PRIMARY KEY, name TEXT);
        CREATE TABLE line(id INTEGER PRIMARY KEY, lineIndex INTEGER);
        CREATE TABLE schema_meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE link(id INTEGER PRIMARY KEY AUTOINCREMENT, sourceBookId INTEGER,
            targetBookId INTEGER, sourceLineId INTEGER, targetLineId INTEGER,
            connectionTypeId INTEGER, baseProvenance INTEGER);
    """)
    conn.executemany("INSERT INTO source VALUES(?,?)", sources)
    conn.executemany("INSERT INTO schema_meta VALUES(?,?)", schema_meta)
    conn.executemany("INSERT INTO book(id,heRef,dependenceType,collectiveTitleHe,"
                     "collectiveTitleEn,isBaseBook,orderIndex,sourceId) "
                     "VALUES(?,?,?,?,?,?,?,?)", books)
    conn.executemany("INSERT INTO book_base_text VALUES(?,?)", bbt)
    conn.executemany("INSERT INTO connection_type VALUES(?,?)", conn_types)
    conn.executemany("INSERT INTO line VALUES(?,?)", lines)
    conn.executemany("INSERT INTO link(sourceBookId,targetBookId,sourceLineId,"
                     "targetLineId,connectionTypeId,baseProvenance) VALUES(?,?,?,?,?,?)", links)
    conn.commit()
    conn.close()


# --- רגרסיית מפה: פרימרי של ספר מאוחר מנצח alias של ספר מוקדם ------------------
def test_primary_beats_earlier_alias():
    print("build_normalized_title_to_bookid: primary מנצח alias מוקדם")
    early = common.SchemaBook()
    early.he_title, early.en_title, early.alias_keys = "משנה מעילה", "Mishnah Meilah", ["meilah"]
    late = common.SchemaBook()
    late.he_title, late.en_title, late.alias_keys = "Meilah", "Meilah", []
    # early לפני late (סדר priority); ל-late פרימרי "meilah" שמתנגש ב-alias של early.
    m = common.build_normalized_title_to_bookid([(early, 87), (late, 137)])
    _check("primary של ספר מאוחר (137) מנצח alias של ספר מוקדם (87)",
           m["meilah"] == 137, f"got {m.get('meilah')}")
    # ואם היה מעבר משולב פר-ספר, early היה תופס "meilah"=87 — הרגרסיה מוודאת שלא.


# --- resolve_schemas_dir: סדר קבוע + סינון export/ עם toc בלבד -----------------
def test_resolve_order():
    print("resolve_schemas_dir: מדלג על export/ (toc בלבד) ובוחר export/schemas")
    with tempfile.TemporaryDirectory() as tmp:
        sefaria = os.path.join(tmp, "sefaria")
        schemas = os.path.join(sefaria, "export", "schemas")
        os.makedirs(schemas)
        _write_schema(schemas, "A.json", "A", "אלף")
        # export/ מכיל *.json שאינו schema (מלכודת ה-baseline).
        with open(os.path.join(sefaria, "export", "table_of_contents.json"), "w") as fh:
            json.dump([], fh)
        for label, arg in (("sefaria", sefaria),
                           ("export", os.path.join(sefaria, "export")),
                           ("schemas", schemas)):
            _check(f"--sefaria-dir כצורת {label} → export/schemas",
                   common.resolve_schemas_dir(arg) == schemas)
        # תיקייה עם *.json אך ללא schema → כשל בקול.
        only_toc = os.path.join(tmp, "onlytoc")
        os.makedirs(only_toc)
        with open(os.path.join(only_toc, "x.json"), "w") as fh:
            json.dump([1, 2], fh)
        rc = subprocess.run([sys.executable, "-c",
                             f"import sys;sys.path.insert(0,{_QA!r});import common;"
                             f"common.resolve_schemas_dir({only_toc!r})"],
                            capture_output=True).returncode
        _check("תיקייה ללא schema-books → יציאה!=0", rc != 0)


# --- check2: pass + fail (עודף ב-DB) ------------------------------------------
def test_check2():
    print("check2_book_base_text: pass + fail")
    with tempfile.TemporaryDirectory() as tmp:
        schemas = os.path.join(tmp, "schemas")
        os.makedirs(schemas)
        _write_schema(schemas, "dep.json", "Commentary A", "פירוש א", base_he="בסיס")
        _write_schema(schemas, "base.json", "Base", "בסיס")
        books = [(10, "פירוש א", None, None, None, 0, 999, SRC_SEFARIA),
                 (20, "בסיס", None, None, None, 1, 1, SRC_SEFARIA)]
        db_ok = os.path.join(tmp, "ok.db")
        _make_db(db_ok, books=books, bbt=[(10, 20)])
        rc, _ = _run("check2_book_base_text.py", "--db", db_ok, "--sefaria-dir", schemas)
        _check("check2 pass (expected==DB)", rc == 0)
        db_bad = os.path.join(tmp, "bad.db")
        _make_db(db_bad, books=books, bbt=[(10, 20), (10, 20), (20, 10)])
        rc, out = _run("check2_book_base_text.py", "--db", db_bad, "--sefaria-dir", schemas)
        _check("check2 fail על זוג עודף ב-DB", rc != 0, out.strip().splitlines()[-1:])


# --- check6: pass + fail (matched==0, unmatched-with-meta) ---------------------
def test_check6():
    print("check6_metadata_rowbyrow: pass + fail")
    with tempfile.TemporaryDirectory() as tmp:
        schemas = os.path.join(tmp, "schemas")
        os.makedirs(schemas)
        _write_schema(schemas, "a.json", "A", "ספר א", dependence="commentary",
                      coll_he="רש\"י", coll_en="Rashi")
        ok_books = [(1, "ספר א", "commentary", "רש\"י", "Rashi", 0, 1, SRC_SEFARIA)]
        db_ok = os.path.join(tmp, "ok.db")
        _make_db(db_ok, books=ok_books)
        rc, _ = _run("check6_metadata_rowbyrow.py", "--db", db_ok, "--sefaria-dir", schemas)
        _check("check6 pass (מטא-דאטה תואם)", rc == 0)
        # matched==0: heRef לא תואם אף heTitle.
        db_nomatch = os.path.join(tmp, "nomatch.db")
        _make_db(db_nomatch, books=[(1, "ספר לא-קיים", None, None, None, 0, 1, SRC_SEFARIA)])
        rc, out = _run("check6_metadata_rowbyrow.py", "--db", db_nomatch, "--sefaria-dir", schemas)
        _check("check6 fail על matched==0", rc != 0, out.strip().splitlines()[-1:])
        # unmatched-with-meta: ספר עם dependenceType ללא schema (וגם ספר תואם, שמתאם>0).
        db_orphan = os.path.join(tmp, "orphan.db")
        _make_db(db_orphan, books=ok_books + [(2, "יתום", "targum", None, None, 0, 2, SRC_SEFARIA)])
        rc, out = _run("check6_metadata_rowbyrow.py", "--db", db_orphan, "--sefaria-dir", schemas)
        _check("check6 fail על מטא-דאטה ללא schema תואם", rc != 0, out.strip().splitlines()[-1:])


# --- check7: pass + fail (כיוון הפוך, דגימה לא-מייצגת) -------------------------
def test_check7():
    print("check7_provenance: pass + fail")
    conn_types = [(1, "COMMENTARY")]
    lines = [(1, 1), (2, 1), (3, 1), (4, 1)]
    # S=1 (מקור), T2=2 declared, T1=3 inferred, T0=4 none.
    books = [(1, "S", None, None, None, 0, 5, SRC_SEFARIA),
             (2, "T2", None, None, None, 1, 1, SRC_SEFARIA),
             (3, "T1", None, None, None, 0, 2, SRC_SEFARIA),
             (4, "T0", None, None, None, 0, 3, SRC_SEFARIA)]
    links = [(1, 2, 1, 2, 1, 2), (1, 3, 1, 3, 1, 1), (1, 4, 1, 4, 1, 0)]
    with tempfile.TemporaryDirectory() as tmp:
        # pass: bbt בכיוון הנכון (bookId=target תלוי, baseBookId=source בסיס) לזוג המוצהר.
        db_ok = os.path.join(tmp, "ok.db")
        _make_db(db_ok, books=books, bbt=[(2, 1)], links=links,
                 conn_types=conn_types, lines=lines)
        rc, out = _run("check7_provenance.py", "--db", db_ok)
        _check("check7 pass (כיוון תקין + דגימה מייצגת)", rc == 0, out.strip().splitlines()[-1:])
        # fail כיוון הפוך: bbt הפוך → זוג-הקישור המוצהר לא נמצא בהתמצאות הצפויה.
        db_rev = os.path.join(tmp, "rev.db")
        _make_db(db_rev, books=books, bbt=[(1, 2)], links=links,
                 conn_types=conn_types, lines=lines)
        rc, out = _run("check7_provenance.py", "--db", db_rev)
        _check("check7 fail על קישור מוצהר בכיוון הפוך", rc != 0, out.strip().splitlines()[-1:])
        # fail דגימה לא-מייצגת: אין ספר-מקור עם שלוש הדרגות (רק {2,0}).
        db_norep = os.path.join(tmp, "norep.db")
        _make_db(db_norep, books=books, bbt=[(2, 1)],
                 links=[(1, 2, 1, 2, 1, 2), (1, 4, 1, 4, 1, 0)],
                 conn_types=conn_types, lines=lines)
        rc, out = _run("check7_provenance.py", "--db", db_norep)
        _check("check7 fail על דגימה לא-מייצגת (אין 3 דרגות)", rc != 0,
               out.strip().splitlines()[-1:])


# --- רגרסיית סינון source=Sefaria (בדיקות 1/2/6) -------------------------------
def test_source_filter_regression():
    print("סינון source=Sefaria: ספר לא-Sefaria החולק heRef עם schema מוחרג")
    with tempfile.TemporaryDirectory() as tmp:
        schemas = os.path.join(tmp, "schemas")
        os.makedirs(schemas)
        _write_schema(schemas, "dep.json", "Commentary A", "פירוש א", base_he="בסיס",
                      dependence="commentary", coll_he="רש\"י", coll_en="Rashi")
        _write_schema(schemas, "base.json", "Base", "בסיס")
        _write_schema(schemas, "coincide.json", "Coincide", "ספר חיצוני",
                      dependence="commentary")
        # שני ספרי MoreBooks עם heRef שזהה במקרה ל-heTitle של schema, מטא-דאטה NULL:
        # id 5 ("בסיס", נסרק ראשון) — בלי הסינון היה נתפס כיעד רזולוציה ב-check2;
        # id 6 ("ספר חיצוני", schema עם dependence) — בלי הסינון היה מנפח את expected
        # ב-check1 ונכשל ב-check6 על NULL מול "commentary". עם הסינון — מוחרגים.
        books = [(5, "בסיס", None, None, None, 0, 1, SRC_MOREBOOKS),
                 (6, "ספר חיצוני", None, None, None, 0, 2, SRC_MOREBOOKS),
                 (10, "פירוש א", "commentary", "רש\"י", "Rashi", 0, 999, SRC_SEFARIA),
                 (20, "בסיס", None, None, None, 1, 3, SRC_SEFARIA)]
        db = os.path.join(tmp, "mixed.db")
        _make_db(db, books=books, bbt=[(10, 20)])

        rc, out = _run("check1_dependence_count.py", "--db", db, "--sefaria-dir", schemas)
        _check("check1 pass: ספר לא-Sefaria אינו נספר ב-expected", rc == 0,
               out.strip().splitlines()[-1:])
        rc, out = _run("check2_book_base_text.py", "--db", db, "--sefaria-dir", schemas)
        _check("check2 pass: הרזולוציה מדלגת על ספר לא-Sefaria בעל אותו heRef", rc == 0,
               out.strip().splitlines()[-1:])
        rc, out = _run("check6_metadata_rowbyrow.py", "--db", db, "--sefaria-dir", schemas)
        _check("check6 pass: מטא-דאטה NULL של ספר לא-Sefaria אינו pass/fail", rc == 0,
               out.strip().splitlines()[-1:])
        _check("check6 מונה רק ספרי Sefaria (matched=2)",
               "books שהותאמו ל-schema: 2" in out, out.strip().splitlines()[:3])

        # DB ללא שורת source בשם 'Sefaria' → כשל בקול.
        db_nosrc = os.path.join(tmp, "nosrc.db")
        _make_db(db_nosrc, books=[(1, "פירוש א", None, None, None, 0, 1, 7)],
                 sources=((7, "MoreBooks"),))
        rc, out = _run("check6_metadata_rowbyrow.py", "--db", db_nosrc, "--sefaria-dir", schemas)
        _check("אין שורת source='Sefaria' → יציאה!=0", rc != 0, out.strip().splitlines()[-1:])


# --- check5: אימות דו"ח מדדי הייבוא בצורה החדשה (ddb1f24) -----------------------
def _write_metrics(path, inserted, persisted=None, schema_version=None, db_version=None):
    # ברירת מחדל ל-persisted: זהה ל-written פר-סוג (Σ שווה, האינווריאנט מתקיים).
    if persisted is None:
        persisted = {name: v["written"] for name, v in inserted.items()}
    report = {"db_schema_version": schema_version, "db_version": db_version,
              "db_size_bytes": 12345,
              "insertedByType": inserted, "persistedByType": persisted}
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False)


def _write_raw(path, obj):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False)


def test_check5():
    print("check5_import_metrics: pass + fail (צורת inserted/persisted)")
    with tempfile.TemporaryDirectory() as tmp:
        def mp(name, inserted, **kw):
            p = os.path.join(tmp, name)
            _write_metrics(p, inserted, **kw)
            return p

        ok = mp("ok.json", {
            "COMMENTARY": {"rowsRead": 10, "dropped": 2, "resolvedPairs": 9, "written": 8},
            "OTHER": {"rowsRead": 5, "dropped": 5, "resolvedPairs": 3, "written": 0}})
        rc, out = _run("check5_import_metrics.py", "--metrics", ok)
        _check("check5 pass (אינווריאנטים מתקיימים)", rc == 0, out.strip().splitlines()[-1:])

        # demotion: written COMMENTARY=8 → persisted RELATED=8; Σ נשמר.
        demote = mp("demote.json",
                    {"COMMENTARY": {"rowsRead": 10, "dropped": 2, "resolvedPairs": 9, "written": 8}},
                    persisted={"RELATED": 8})
        rc, out = _run("check5_import_metrics.py", "--metrics", demote)
        _check("check5 pass על demotion (Σwritten==Σpersisted)", rc == 0,
               out.strip().splitlines()[-1:])

        bad_drop = mp("drop.json", {
            "COMMENTARY": {"rowsRead": 3, "dropped": 4, "resolvedPairs": 0, "written": 0}})
        rc, out = _run("check5_import_metrics.py", "--metrics", bad_drop)
        _check("check5 fail על dropped>rowsRead", rc != 0, out.strip().splitlines()[-1:])

        bad_neg = mp("neg.json", {
            "COMMENTARY": {"rowsRead": 3, "dropped": 0, "resolvedPairs": -1, "written": 0}})
        rc, out = _run("check5_import_metrics.py", "--metrics", bad_neg)
        _check("check5 fail על ערך שלילי", rc != 0, out.strip().splitlines()[-1:])

        # Σwritten > ΣresolvedPairs — גלובלית (persisted תואם ל-written כדי לבודד את השגיאה).
        bad_res = mp("res.json", {
            "OTHER": {"rowsRead": 4, "dropped": 0, "resolvedPairs": 1, "written": 0},
            "COMMENTARY": {"rowsRead": 0, "dropped": 0, "resolvedPairs": 0, "written": 2}})
        rc, out = _run("check5_import_metrics.py", "--metrics", bad_res)
        _check("check5 fail על Σwritten>ΣresolvedPairs", rc != 0, out.strip().splitlines()[-1:])

        # Σwritten != Σpersisted (חדש) — persisted מזייף סכום.
        bad_sum = mp("sum.json",
                     {"COMMENTARY": {"rowsRead": 10, "dropped": 0, "resolvedPairs": 9, "written": 8}},
                     persisted={"COMMENTARY": 7})
        rc, out = _run("check5_import_metrics.py", "--metrics", bad_sum)
        _check("check5 fail על Σwritten!=Σpersisted", rc != 0, out.strip().splitlines()[-1:])

        bad_key = mp("key.json",
                     {"COMMENTARY": {"rowsRead": 1, "dropped": 0, "resolvedPairs": 1}},
                     persisted={"COMMENTARY": 1})
        rc, out = _run("check5_import_metrics.py", "--metrics", bad_key)
        _check("check5 fail על מפתח written חסר", rc != 0, out.strip().splitlines()[-1:])

        # דחיית הצורה הישנה (perType) בקול.
        old = os.path.join(tmp, "old.json")
        _write_raw(old, {"perType": {"COMMENTARY": {
            "rowsRead": 1, "dropped": 0, "resolvedPairs": 1, "written": 1}}})
        rc, out = _run("check5_import_metrics.py", "--metrics", old)
        _check("check5 fail על צורת perType מיושנת", rc != 0 and "ddb1f24" in out,
               out.strip().splitlines()[-1:])

        # persistedByType חסר לגמרי → צורה לא תקינה.
        no_persist = os.path.join(tmp, "nopersist.json")
        _write_raw(no_persist, {"db_schema_version": None, "db_version": None,
                                "db_size_bytes": 1, "insertedByType": {
                                    "COMMENTARY": {"rowsRead": 1, "dropped": 0,
                                                   "resolvedPairs": 1, "written": 1}}})
        rc, out = _run("check5_import_metrics.py", "--metrics", no_persist)
        _check("check5 fail על היעדר persistedByType", rc != 0, out.strip().splitlines()[-1:])

        # הצלבת DB (≥): 3 קישורי COMMENTARY ב-DB, persisted=2 → pass; persisted=4 → fail.
        db = os.path.join(tmp, "links.db")
        _make_db(db, conn_types=[(1, "COMMENTARY")],
                 links=[(1, 2, 1, 2, 1, 0), (1, 3, 1, 3, 1, 0), (1, 4, 1, 4, 1, 0)])
        cross_ok = mp("cross_ok.json",
                      {"COMMENTARY": {"rowsRead": 2, "dropped": 0, "resolvedPairs": 2, "written": 2}})
        rc, out = _run("check5_import_metrics.py", "--metrics", cross_ok, "--db", db)
        _check("check5 pass הצלבת DB (DB=3 ≥ persisted=2)", rc == 0, out.strip().splitlines()[-1:])
        cross_bad = mp("cross_bad.json",
                       {"COMMENTARY": {"rowsRead": 4, "dropped": 0, "resolvedPairs": 4, "written": 4}})
        rc, out = _run("check5_import_metrics.py", "--metrics", cross_bad, "--db", db)
        _check("check5 fail הצלבת DB (DB=3 < persisted=4)", rc != 0, out.strip().splitlines()[-1:])

        # --sefaria-stage: DB=3 מול persisted=3 → pass (==); persisted=2 → fail (== נדרש).
        stage_ok = mp("stage_ok.json",
                      {"COMMENTARY": {"rowsRead": 3, "dropped": 0, "resolvedPairs": 3, "written": 3}})
        rc, out = _run("check5_import_metrics.py", "--metrics", stage_ok, "--db", db,
                       "--sefaria-stage")
        _check("check5 pass --sefaria-stage (DB=3 == persisted=3)", rc == 0,
               out.strip().splitlines()[-1:])
        rc, out = _run("check5_import_metrics.py", "--metrics", cross_ok, "--db", db,
                       "--sefaria-stage")
        _check("check5 fail --sefaria-stage (DB=3 != persisted=2)", rc != 0,
               out.strip().splitlines()[-1:])

        # הצלבת schema_meta: ערכים לא-null מוצלבים מול ה-DB.
        db_meta = os.path.join(tmp, "meta.db")
        _make_db(db_meta, conn_types=[(1, "COMMENTARY")],
                 links=[(1, 2, 1, 2, 1, 0), (1, 3, 1, 3, 1, 0)],
                 schema_meta=[("db_schema_version", "2"), ("db_version", "15")])
        meta_ok = mp("meta_ok.json",
                     {"COMMENTARY": {"rowsRead": 2, "dropped": 0, "resolvedPairs": 2, "written": 2}},
                     schema_version="2", db_version="15")
        rc, out = _run("check5_import_metrics.py", "--metrics", meta_ok, "--db", db_meta)
        _check("check5 pass הצלבת schema_meta תואמת", rc == 0, out.strip().splitlines()[-1:])
        meta_bad = mp("meta_bad.json",
                      {"COMMENTARY": {"rowsRead": 2, "dropped": 0, "resolvedPairs": 2, "written": 2}},
                      schema_version="3", db_version="15")
        rc, out = _run("check5_import_metrics.py", "--metrics", meta_bad, "--db", db_meta)
        _check("check5 fail הצלבת schema_meta לא-תואמת", rc != 0, out.strip().splitlines()[-1:])


# --- run_all: --require-all הופך דילוג לכשל --------------------------------------
def test_run_all_require_all():
    print("run_all: --require-all הופך דילוג לכשל; ברירת המחדל מדלגת")
    with tempfile.TemporaryDirectory() as tmp:
        # DB שמספק את בדיקות 3/7/8 (פיקסטורת check7 העוברת + סוג ELUCIDATION עם 0 קישורים).
        # ללא --sefaria-dir/--metrics → check5 ובדיקות 1/2/6 מדולגות.
        db = os.path.join(tmp, "d.db")
        _make_db(db,
                 books=[(1, "S", None, None, None, 0, 5, SRC_SEFARIA),
                        (2, "T2", None, None, None, 1, 1, SRC_SEFARIA),
                        (3, "T1", None, None, None, 0, 2, SRC_SEFARIA),
                        (4, "T0", None, None, None, 0, 3, SRC_SEFARIA)],
                 bbt=[(2, 1)],
                 links=[(1, 2, 1, 2, 1, 2), (1, 3, 1, 3, 1, 1), (1, 4, 1, 4, 1, 0)],
                 conn_types=[(1, "COMMENTARY"), (2, "ELUCIDATION")],
                 lines=[(1, 1), (2, 1), (3, 1), (4, 1)])
        # בלי --metrics ובלי --sefaria-dir: check5 + 1/2/6 מדולגות.
        rc, out = _run("run_all.py", "--db", db)
        _check("run_all ברירת מחדל: דילוג מותר (יציאה 0)", rc == 0, out.strip().splitlines()[-3:])
        _check("run_all ברירת מחדל: מנסח 'דולגו' ולא 'כל הבדיקות עברו'",
               "דולגו" in out and "כל הבדיקות עברו" not in out, out.strip().splitlines()[-3:])
        rc, out = _run("run_all.py", "--db", db, "--require-all")
        _check("run_all --require-all: דילוג → כשל (יציאה!=0)", rc != 0,
               out.strip().splitlines()[-3:])
        _check("run_all --require-all: מפרט את הארגומנט המפעיל (--metrics)",
               "--metrics" in out, out.strip().splitlines()[-4:])


def main():
    for t in (test_primary_beats_earlier_alias, test_resolve_order,
              test_check2, test_check6, test_check7,
              test_source_filter_regression, test_check5,
              test_run_all_require_all):
        t()
    print()
    if _FAILURES:
        print(f"FAIL: {len(_FAILURES)} תרחישים נכשלו: {_FAILURES}")
        sys.exit(1)
    print("PASS: כל תרחישי המטריצה הסינתטית עברו")
    sys.exit(0)


if __name__ == "__main__":
    main()
