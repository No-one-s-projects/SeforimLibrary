# סקריפטי QA — ייבוא v2.2 (סעיף 10 בתוכנית)

כלי ייחוס versioned לבדיקות ה-DB של `SEFARIA_METADATA_IMPORT_PLAN.md` סעיף 10.
עד כה הם התקיימו רק ב-scratchpad של סשן (`claim_a..f.py`) ולא היו ניתנים לשחזור
(סעיף 10א). כאן הם קוד versioned. stdlib בלבד (`sqlite3`, `json`, `argparse`).

## שימוש כללי

כל סקריפט מקבל `--db PATH`. בדיקות ההשוואה לסכמות מקבלות גם `--sefaria-dir PATH`.

`--sefaria-dir` מצביע על ארטיפקטי ה-Sefaria export של אותה בנייה. הפריסה האמיתית:

```
generator/sefariasqlite/build/sefaria/export/schemas/*.json
```

אפשר להעביר את `.../sefaria`, את `.../export` או את `.../schemas` ישירות — הסקריפט
מנסה את שלוש הפריסות ונכשל בקול עם רשימת הנתיבים שנוסו. קובץ schema לא-קריא
(למשל `Sheet.json` בגודל 0 בייצוא האמיתי) מדולג עם אזהרה — שכפול דטרמיניסטי של
`runCatching` פר-קובץ ב-`SefariaBookPayloadReader.kt:33-49`, לא היוריסטיקה.

הרצת הכול:

```
python3 scripts/qa/run_all.py --db seforim.db \
  --sefaria-dir generator/sefariasqlite/build/sefaria/export
```

בלי `--sefaria-dir` — בדיקות 1/2/6 מדולגות (רק 3/7/8 רצות).

קוד יציאה: `0` = עבר, שונה מ-`0` = כשל, עם הודעה ברורה ל-stderr (fail loudly, ללא fallbacks).

## ברירת המחדל: expected מחושב מהבנייה, לא מ-baseline קשיח (סעיף 10א)

ה-exporter משכפל את Sefaria-Project HEAD ללא הצמדה
(`05_clone_sefaria_project.sh` — `git clone --depth 1`), ולכן המספרים
`4,941 / 5,437 / 13,056 / 20` נכונים ל-snapshot הנוכחי בלבד. (ערך התוכנית ‏5,426
ל-book_base_text היה ארטיפקט מדידה — ראו סעיף הרזולוציה למטה.) לכן:

- **ברירת מחדל:** כל בדיקה מחשבת את ה-`expected` מארטיפקטי אותה בנייה (schemas)
  ומשווה שורה-שורה מול ה-DB. זו ההשוואה המחייבת.
- ה-baselines הקשיחים מודפסים כערכי ייחוס בלבד, ונאכפים **רק** עם הדגל
  האופציונלי `--expect-snapshot` (רלוונטי כשבונים מה-snapshot הנוכחי).

## מיפוי סקריפט ↔ בדיקת סעיף 10

| סקריפט | בדיקה | מה נבדק | ארגומנטים |
|---|---|---|---|
| `check1_dependence_count.py` | 10.1 | `book.dependenceType` מול schemas; ‏baseline 4,941 ופילוח | `--db --sefaria-dir [--expect-snapshot]` |
| `check2_book_base_text.py` | 10.2 | `book_base_text` (מוצהר) עם רזולוציית alt-titles בסדר-priority; ‏5,437 | `--db --sefaria-dir [--priority-list] [--expect-snapshot]` |
| `check3_elucidation.py` | 10.3 | 0 קישורי ELUCIDATION ב-DB | `--db` |
| `check6_metadata_rowbyrow.py` | 10.6 | שורה-שורה לפי heRef: dependenceType, collectiveTitleHe/En | `--db --sefaria-dir` |
| `check7_provenance.py` | 10.7 | baseProvenance 1/2, זוגות מוסקים, עקביות מול book_base_text, סדר SOURCE | `--db [--expect-snapshot]` |
| `check8_integrity.py` | 10.8 | `PRAGMA quick_check` + `PRAGMA foreign_key_check` | `--db` |

## התאמת schemas ↔ DB לפי heRef (לא title)

שישה ספרים תלויים משוני-שם בייבוא (ר' סעדיה גאון על בראשית/עזרא/נחמיה, אדרת
אליהו, חומת אנך על דה"י א/ב) — ה-`title` ב-DB שונה מ-heTitle של הסכמה, אבל
`book.heRef` נשאר `payload.heTitle`. לכן כל הצלבת schemas↔DB בסקריפטים
(בדיקות 1/2/6) נעשית לפי **heRef** — כפי שה-baseline ‏4,941 נקבע בתוכנית
(עובדה 5: התאמת title נותנת 4,935 שגוי).

## רזולוציית alt-titles (בדיקה 2 — קריטי)

התאמה נאיבית נותנת 5,420 במקום 5,426 — 6 בסיסים נפתרים רק דרך וריאנט שם.
הסקריפט משכפל את `normalizedTitleToBookId` של `SefariaDirectImporter.kt` (המעבר הדחוי):

1. `normalize_title_key` זהה ל-`SefariaImportText.kt` (הסרת גרש/גרשיים/מרכאות,
   lowercase, כיווץ רווחים, `_`→רווח, trim).
2. מהסכמות מחלצים לכל ספר: `base_text_titles` (מוצהר, en+he מנורמלים) ו-aliases
   (`titleVariants`+`heTitleVariants`, פרט ל-HTML), בדיוק כמו
   `SefariaBookPayloadReader.kt`.
3. הספרים מצומצמים לאלה שקיימים ב-DB (התאמת heTitle מנורמל ל-`book.heRef` —
   מיישם דה-פקטו את ה-blacklist ועמיד לשינויי-שם בייבוא). **סדר הספרים משוכפל
   מ-`applyPriorityOrdering` של היבואן**: תחילה לפי `priority.txt` (ה-resource
   המגורסן ברפו, `generator/sefariasqlite/src/jvmMain/resources/priority.txt`,
   ברירת המחדל של `--priority-list`), אחריו השאר. הנחת סדר שיורית: סדר "השאר"
   אצל היבואן הוא סדר סריקת קבצי הטקסט — כאן סדר שמות קובצי schemas; משפיע רק
   על התנגשויות מפתח בין ספרים לא-priority. בונים מפה `normalized→bookId`
   **פר-ספר משולב**, כמו `SefariaDirectImporter.kt:278-286`: לכל ספר בתורו —
   הפרימריז שלו (heTitle/enTitle) ואז ה-aliases שלו, הכול `putIfAbsent`.
4. כל מפתח בסיס מוצהר נפתר דרך המפה (כולל aliases) → זוג `(bookId, baseBookId)`.
   קבוצת הזוגות מושווית שורה-שורה מול `book_base_text`.

### מדוע 5,437 ולא 5,426 (ממצא ההרצה האמיתית על סכמה 2)

הרצה מול הבנייה האמיתית נתנה ‏DB=5,437 מול צפי-סקריפט ישן 5,426: ‏11 זוגות
"עודפים", כולם ‏11 מפרשי תלמוד מעילה (רש"י/תוספות/רבינו גרשום/...) עם
`base_text_titles={"en":"Meilah","he":"מעילה"}` — שני מפתחות מנורמלים שנפתרים
אצל היבואן ל**שני ספרים שונים**: ‏"meilah" → משנה מעילה (id 87, דרך alias,
כי המשנה נטענת לפני התלמוד ב-priority.txt) ו-"מעילה" → מעילה (id 137, primary).
כלומר שני זוגות לספר — התנהגות יבואן דטרמיניסטית. אחרי שכפול סדר-ה-priority
בסקריפט: ‏expected==DB==5,437 שורה-שורה. ‏5,426 של התוכנית היה ארטיפקט של שיטת
מדידה ללא הסדר הזה (התוכנית עצמה לא עודכנה — נכון ל-snapshot זה הערך הוא 5,437).

## בדיקות שאינן סקריפטים כאן (בכוונה)

- **4 (enum) ו-9 (build נכשל על סוג לא-ממופה):** מכוסות ב-unit tests של המאגר
  (round-trip `fromString`, יציבות ordinal, `fromKnownStringOrNull`, guard סינתטי).
- **5 (מוני importer לפי סוג): לא ניתן לסקריפט כרגע.** ‏`SefariaLinksImporter.kt`
  מדווח בסיכום רק מוני טווחים (`rangedRowsSeen/rangesResolved/rangeEndUnresolved/
  rangeReversed/rangedRowsDropped`, ‏שורות 37–42, 127–133) וספירת קבצים —
  **אין** מונה per-connection-type של שורות-שנקראו/שנפלו/זוגות-שנפתרו/רשומות-שנכתבו.
  ללא לוגינג כזה אין מקור לפרסור. הבדיקה תהפוך לבת-סקריפט רק אחרי שהיבואן יפלוט
  מונים לפי סוג בסוף שלב ייבוא הקישורים של ספריא.
- **10 (E2E: full download + patch 2→2 דרך ה-updater):** קומיט 13, לא כאן.

## אימות

הסקריפטים אומתו מול DB סינתטי (טבלאות/schemas מינימליים) בתרחיש עובר ובתרחיש
נכשל לכל בדיקה — כולם מדווחים כשל בקול ויוצאים בקוד שונה מ-0. בנוסף רצו מול
בנייה אמיתית של סכמה 2 (`build/seforim.db`): כל 6 הבדיקות עוברות; ‏4,941 / 5,437 /
13,056+20 / ‏ELUCIDATION=0 אומתו. ה-E2E המלא (patch 2→2 דרך ה-updater) — קומיט 13.
