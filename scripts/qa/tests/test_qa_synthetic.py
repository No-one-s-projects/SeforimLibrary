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


def _make_db(path, books=(), bbt=(), links=(), conn_types=(), lines=()):
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE book(id INTEGER PRIMARY KEY, heRef TEXT, dependenceType TEXT,
            collectiveTitleHe TEXT, collectiveTitleEn TEXT, isBaseBook INTEGER DEFAULT 0,
            orderIndex REAL DEFAULT 999);
        CREATE TABLE book_base_text(bookId INTEGER, baseBookId INTEGER);
        CREATE TABLE connection_type(id INTEGER PRIMARY KEY, name TEXT);
        CREATE TABLE line(id INTEGER PRIMARY KEY, lineIndex INTEGER);
        CREATE TABLE link(id INTEGER PRIMARY KEY AUTOINCREMENT, sourceBookId INTEGER,
            targetBookId INTEGER, sourceLineId INTEGER, targetLineId INTEGER,
            connectionTypeId INTEGER, baseProvenance INTEGER);
    """)
    conn.executemany("INSERT INTO book(id,heRef,dependenceType,collectiveTitleHe,"
                     "collectiveTitleEn,isBaseBook,orderIndex) VALUES(?,?,?,?,?,?,?)", books)
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
        books = [(10, "פירוש א", None, None, None, 0, 999),
                 (20, "בסיס", None, None, None, 1, 1)]
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
        ok_books = [(1, "ספר א", "commentary", "רש\"י", "Rashi", 0, 1)]
        db_ok = os.path.join(tmp, "ok.db")
        _make_db(db_ok, books=ok_books)
        rc, _ = _run("check6_metadata_rowbyrow.py", "--db", db_ok, "--sefaria-dir", schemas)
        _check("check6 pass (מטא-דאטה תואם)", rc == 0)
        # matched==0: heRef לא תואם אף heTitle.
        db_nomatch = os.path.join(tmp, "nomatch.db")
        _make_db(db_nomatch, books=[(1, "ספר לא-קיים", None, None, None, 0, 1)])
        rc, out = _run("check6_metadata_rowbyrow.py", "--db", db_nomatch, "--sefaria-dir", schemas)
        _check("check6 fail על matched==0", rc != 0, out.strip().splitlines()[-1:])
        # unmatched-with-meta: ספר עם dependenceType ללא schema (וגם ספר תואם, שמתאם>0).
        db_orphan = os.path.join(tmp, "orphan.db")
        _make_db(db_orphan, books=ok_books + [(2, "יתום", "targum", None, None, 0, 2)])
        rc, out = _run("check6_metadata_rowbyrow.py", "--db", db_orphan, "--sefaria-dir", schemas)
        _check("check6 fail על מטא-דאטה ללא schema תואם", rc != 0, out.strip().splitlines()[-1:])


# --- check7: pass + fail (כיוון הפוך, דגימה לא-מייצגת) -------------------------
def test_check7():
    print("check7_provenance: pass + fail")
    conn_types = [(1, "COMMENTARY")]
    lines = [(1, 1), (2, 1), (3, 1), (4, 1)]
    # S=1 (מקור), T2=2 declared, T1=3 inferred, T0=4 none.
    books = [(1, "S", None, None, None, 0, 5), (2, "T2", None, None, None, 1, 1),
             (3, "T1", None, None, None, 0, 2), (4, "T0", None, None, None, 0, 3)]
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


def main():
    for t in (test_primary_beats_earlier_alias, test_resolve_order,
              test_check2, test_check6, test_check7):
        t()
    print()
    if _FAILURES:
        print(f"FAIL: {len(_FAILURES)} תרחישים נכשלו: {_FAILURES}")
        sys.exit(1)
    print("PASS: כל תרחישי המטריצה הסינתטית עברו")
    sys.exit(0)


if __name__ == "__main__":
    main()
