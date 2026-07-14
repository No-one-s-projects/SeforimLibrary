"""עזרי-QA משותפים: נירמול כותרות, קריאת schemas, פתיחת DB. stdlib בלבד."""
import json
import os
import re
import sqlite3
import sys

_WS = re.compile(r"\s+")


def normalize_title_key(value):
    # שכפול מדויק של normalizeTitleKey ב-SefariaImportText.kt.
    if value is None or value.strip() == "":
        return None
    s = (value.replace('"', "").replace("'", "")
         .replace("׳", "").replace("״", ""))
    s = s.lower()
    s = _WS.sub(" ", s)
    s = s.replace("_", " ")
    return s.strip()


def _str_or_none(v):
    return v if isinstance(v, str) and v != "" else None


def sanitize_folder(name):
    # שכפול sanitizeFolder (SefariaImportText.kt): '"'→'״' + trim.
    if name is None or name.strip() == "":
        return ""
    return name.replace('"', "״").strip()


def flatten_talmud_categories(parts):
    # שכפול flattenTalmudCategories (SefariaImportOrdering.kt).
    flattened = []
    idx = 0
    while idx < len(parts):
        part = parts[idx]
        if part == "תלמוד" and idx + 1 < len(parts) and parts[idx + 1] in ("בבלי", "ירושלמי"):
            flattened.append("תלמוד " + parts[idx + 1])
            idx += 2
            continue
        flattened.append(part)
        idx += 1
    return flattened


def normalize_priority_entry(raw):
    # שכפול normalizePriorityEntry (SefariaImportOrdering.kt).
    entry = raw.strip().replace("\\", "/").lstrip("/")
    parts = [sanitize_folder(p) for p in entry.split("/") if p.strip() != ""]
    return "/".join(flatten_talmud_categories(parts))


def normalized_book_path(categories_he, he_title):
    return "/".join([sanitize_folder(c) for c in categories_he] + [sanitize_folder(he_title)])


def default_priority_list_path():
    # priority.txt הוא resource מגורסן באותו repo — נתיב יחסי לסקריפט, לא אבסולוטי.
    return os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "..",
        "generator", "sefariasqlite", "src", "jvmMain", "resources", "priority.txt"))


def load_priority_list(path):
    if not os.path.isfile(path):
        die(f"priority.txt לא נמצא: {path}")
    with open(path, encoding="utf-8") as fh:
        entries = []
        for line in fh:
            s = line.strip()
            if s == "" or s.startswith("#"):
                continue
            n = normalize_priority_entry(s)
            if n != "":
                entries.append(n)
    return entries


def order_books_by_priority(books, priority_entries):
    # שכפול applyPriorityOrdering: מתאימי-priority לפי סדר הרשימה, השאר בסדר הקלט.
    if not priority_entries:
        return list(books)
    lookup = {}
    for b in books:
        lookup[normalized_book_path(b.categories_he, b.he_title)] = b  # אחרון מנצח (associateBy)
    used = set()
    ordered = []
    for entry in priority_entries:
        b = lookup.get(entry)
        if b is not None and entry not in used:
            used.add(entry)
            ordered.append(b)
    for b in books:
        if normalized_book_path(b.categories_he, b.he_title) not in used:
            ordered.append(b)
    return ordered


def _dir_has_schema_books(d):
    # מאתר לפחות schema אחד תקין (dict עם "schema" מקונן); עצירה מוקדמת.
    for fn in os.listdir(d):
        if not fn.endswith(".json"):
            continue
        try:
            with open(os.path.join(d, fn), encoding="utf-8") as fh:
                top = json.load(fh)
        except (ValueError, OSError):
            continue
        if isinstance(top, dict) and isinstance(top.get("schema"), dict):
            return True
    return False


def resolve_schemas_dir(sefaria_dir):
    # מקבל schemas/ ישירות, export/ (מכיל schemas/), או sefaria/ (מכיל export/schemas/).
    # סדר קבוע: schemas תחילה, אחר-כך export/schemas, ורק לבסוף התיקייה עצמה — כדי
    # ש-export/ (המכיל table_of_contents.json בלבד) לא ייבחר בטעות במקום export/schemas.
    if not os.path.isdir(sefaria_dir):
        die(f"--sefaria-dir אינו תיקייה: {sefaria_dir}")
    candidates = [os.path.join(sefaria_dir, "schemas"),
                  os.path.join(sefaria_dir, "export", "schemas"),
                  sefaria_dir]
    attempted = []
    for cand in candidates:
        if not (os.path.isdir(cand) and any(f.endswith(".json") for f in os.listdir(cand))):
            continue
        attempted.append(cand)
        # מועמד עם *.json אך ללא ולו schema אחד (למשל export/ עם toc בלבד) — נמשיך הלאה.
        if _dir_has_schema_books(cand):
            return cand
    die("לא נמצאה תיקיית schemas עם קובצי schema תקינים; נוסו: "
        + ", ".join(attempted or candidates))


def build_normalized_title_to_bookid(schema_dbid):
    # שכפול buildNormalizedTitleToBookId (SefariaDirectImporter.kt): שני מעברים גלובליים
    # על הספרים בסדר-priority — כל הפרימריז (heTitle→enTitle, putIfAbsent) ואז כל
    # ה-aliases (putIfAbsent). הפיצול מבטיח שפרימרי מנצח alias, גם alias של ספר מוקדם.
    # קלט: רשימת (SchemaBook, dbid) בסדר-priority; ערך dbid בערך None מדולג.
    norm_to_id = {}
    for b, dbid in schema_dbid:  # מעבר 1: פרימריז של כל הספרים
        if dbid is None:
            continue
        for t in (b.he_title, b.en_title):
            n = normalize_title_key(t)
            if n is not None:
                norm_to_id.setdefault(n, dbid)
    for b, dbid in schema_dbid:  # מעבר 2: aliases של כל הספרים
        if dbid is None:
            continue
        for a in b.alias_keys:
            norm_to_id.setdefault(a, dbid)
    return norm_to_id


class SchemaBook:
    __slots__ = ("en_title", "he_title", "raw_dependence", "declared_keys",
                 "alias_keys", "collective_he", "collective_en", "path",
                 "categories_he")


def _extract_base_text_keys(top, schema):
    arr = top.get("base_text_titles")
    if not isinstance(arr, list):
        arr = schema.get("base_text_titles")
    if not isinstance(arr, list):
        return []
    keys = []
    for el in arr:
        if isinstance(el, dict):
            for k in ("en", "he"):
                n = normalize_title_key(_str_or_none(el.get(k)))
                if n is not None:
                    keys.append(n)
        elif isinstance(el, str):
            n = normalize_title_key(el)
            if n is not None:
                keys.append(n)
    return list(dict.fromkeys(keys))


def _extract_alias_keys(top, schema):
    out = []
    for key in ("titleVariants", "heTitleVariants"):
        arr = top.get(key)
        if not isinstance(arr, list):
            arr = schema.get(key)
        if not isinstance(arr, list):
            continue
        for el in arr:
            if not isinstance(el, str) or el.strip() == "":
                continue
            if "<" in el or ">" in el:
                continue
            n = normalize_title_key(el)
            if n is not None:
                out.append(n)
    return list(dict.fromkeys(out))


def _extract_raw_dependence(top, schema):
    v = top.get("dependence")
    if not isinstance(v, str):
        v = schema.get("dependence")
    if not isinstance(v, str):
        return None
    v = v.strip().lower()
    return v if v != "" else None


def _file_title(path):
    base = os.path.basename(path)[:-5] if path.endswith(".json") else os.path.basename(path)
    return base.replace("_", " ")


def load_schema_books(schemas_dir):
    # שכפול קריאת SefariaBookPayloadReader.kt: כותרות מ-schema המקונן, dependence/base מ-top עם fallback.
    books = []
    skipped = 0
    for fn in sorted(os.listdir(schemas_dir)):
        if not fn.endswith(".json"):
            continue
        path = os.path.join(schemas_dir, fn)
        try:
            with open(path, encoding="utf-8") as fh:
                top = json.load(fh)
        except (ValueError, OSError) as e:
            # שכפול דטרמיניסטי של runCatching פר-קובץ ב-SefariaBookPayloadReader.kt:33-49
            # (buildSchemaLookup בולע קובץ לא-פריס בשקט; למשל Sheet.json ריק בייצוא האמיתי).
            print(f"אזהרה: schema לא-קריא, מדולג: {fn} ({e})", file=sys.stderr)
            skipped += 1
            continue
        if not isinstance(top, dict):
            continue
        schema = top.get("schema")
        if not isinstance(schema, dict):
            continue
        en = _str_or_none(schema.get("title")) or _file_title(path)
        he = _str_or_none(schema.get("heTitle")) or en
        cats = top.get("heCategories")
        if not isinstance(cats, list):
            cats = schema.get("heCategories")
        cats = [c for c in cats if isinstance(c, str)] if isinstance(cats, list) else []
        ct = top.get("collective_title")
        ct = ct if isinstance(ct, dict) else {}
        b = SchemaBook()
        b.path = path
        b.en_title = en
        b.he_title = he
        # כמו payload.categoriesHe: ‏flattenTalmudCategories(heCategories.map(sanitizeFolder)).
        b.categories_he = flatten_talmud_categories([sanitize_folder(c) for c in cats])
        b.raw_dependence = _extract_raw_dependence(top, schema)
        b.declared_keys = _extract_base_text_keys(top, schema)
        b.alias_keys = _extract_alias_keys(top, schema)
        ch = _str_or_none(ct.get("he"))
        ce = _str_or_none(ct.get("en"))
        b.collective_he = ch.strip() if ch else None
        b.collective_en = ce.strip() if ce else None
        if b.collective_he == "":
            b.collective_he = None
        if b.collective_en == "":
            b.collective_en = None
        books.append(b)
    if skipped:
        print(f"skipped {skipped} unreadable schema files", file=sys.stderr)
    return books


def open_db(db_path):
    if not os.path.isfile(db_path):
        die(f"קובץ DB לא קיים: {db_path}")
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    return conn


def require_columns(conn, table, cols):
    have = {r["name"] for r in conn.execute(f"PRAGMA table_info({table})")}
    if not have:
        die(f"טבלה חסרה ב-DB: {table}")
    missing = [c for c in cols if c not in have]
    if missing:
        die(f"עמודות חסרות בטבלה {table}: {', '.join(missing)}")


def die(msg):
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def ok(msg):
    print(f"PASS: {msg}")
    sys.exit(0)
