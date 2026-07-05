# שרשרת הענפים — מ־`master` ועד `otzaria`

מסמך זה מתעד את ארכיטקטורת הענפים של הפורק. ההזרמה והדחיפה מבוצעות ע"י
[`scripts/cascade-master.sh`](scripts/cascade-master.sh).

## המבנה: היברידי (rebase לבסיס+פיצ'רים, merge-בועות ל-otzaria)

- **שכבת בסיס+פיצ'רים = stack לינארי ב-REBASE.** כל ענף יושב על גב קודמו, כך שכל
  PR ל-upstream מכיל רק את הקומיטים של אותו ענף מעל הקודם. (`master` … `feat/hearot`)
- **`otzaria` = merge-based עם בועות נקיות.** נבנה מחדש בכל cascade: מ-`default_commentators`,
  לכל פיצ'ר נפתחת **בועה** (ה-delta שלו) שממוזגת ב-`--no-ff`, ומעליהן מונחים הקומיטים
  הספציפיים לאוצריא. כך בהיסטוריה של `otzaria` **רואים בבירור איפה כל ענף מתחיל ונגמר**:

```
*   Merge branch 'feat/otzaria-ranged-links-and-alt-toc' into otzaria
|\
| * feat(otzaria): ranged links + alt-toc structures in the otzariasqlite generator
|/
*   Merge branch 'feat/hearot-standalone-books' into otzaria
|\
| * feat(otzaria): import 'הערות' companion files…
|/
*   Merge branch 'feat/ranged-links-and-book-versions' into otzaria
|\
| * fix(versions): flush batches per version file
| * feat(versions): per-edition text storage
| * feat(links): multi-line ranged link support
|/
*   Merge branch 'word-level-link-anchors' into otzaria
|\  …
```

> **הבחנה חשובה:** הבועות ב-`otzaria` הן **עותקי-delta** של הפיצ'רים (SHA חדשים,
> כמו "rebase & merge" בגיטהאב). ענפי הפיצ'ר עצמם (`fix/*`, `perf`, `word-level`,
> `ranged`, `hearot`) נשארים **טהורים ולינאריים** — מוכנים ל-PR ל-upstream.

---

## תרשים השרשרת

```
upstream/master
   │  (ff-only, זהה בדיוק)
   ▼
master → metadata_A → metadata_B → default_commentators        [בסיס — rebase]
                                          │
   ┌──────────────────────────────────────┘
   ▼
fix/category-ids-full-path → fix/book-corpus-talmud → perf/faster-generation
   → word-level-link-anchors → feat/ranged-links-and-book-versions
   → feat/hearot-standalone-books → feat/otzaria-ranged-links-and-alt-toc
                                                                [פיצ'רים — rebase, PR נפרד לכל אחד]
                                          │
                                          ▼   (כל פיצ'ר → בועת merge)
                                       otzaria  = בועות הפיצ'רים + קומיטי-אוצריא בראש
```

## טבלת סיכום

| # | ענף | מעל | קומיטים | ייעוד |
|---|-----|-----|:---:|-------|
| 1 | `master` | `upstream/master` | 0 | מראה מדויק של upstream |
| 2 | `metadata_A` | `master` | 1 | seedAllMetadata (Otzaria PR #84) |
| 3 | `metadata_B` | `metadata_A` | 4 | seedAllMetadata — תיאורים/מו"ל/מקור |
| 4 | `default_commentators` | `metadata_B` | 7 | מפרשי ברירת-מחדל (Otzaria PR #10) |
| 5 | `fix/category-ids-full-path` | `default_commentators` | 1 | תיקון מזהי קטגוריה |
| 6 | `fix/book-corpus-talmud` | ↑ | 1 | תיקון `_book_corpus` לתלמוד |
| 7 | `perf/faster-generation` | ↑ | 2 | TOC ב-batch + ריצת tmpfs |
| 8 | `word-level-link-anchors` | ↑ | 3 | עוגני-מילה לקישורים |
| 9 | `feat/ranged-links-and-book-versions` | ↑ | 3 | קישורי-טווח + גרסאות ספרים |
| 10 | `feat/hearot-standalone-books` | ↑ | 1 | ספרי "הערות" עצמאיים כמפרשים |
| 11 | `feat/otzaria-ranged-links-and-alt-toc` | ↑ | 1 | קישורי-טווח + alt-toc בגנרטור אוצריא |
| — | `otzaria` | (merge של כולם) | 12 (+manifest) | קומיטים ספציפיים לאוצריא, בראש |

---

## פירוט הענפים

### 1. `master`
מראה מדויק (`ff-only`) של `upstream/master` (kdroidFilter/SeforimLibrary). בלי קומיטים
משלו — נקודת העוגן של כל השרשרת. עיקרון: לצמצם סטייה מ-upstream.

### 2–3. `metadata_A` / `metadata_B`
`seedAllMetadata` — post-process שמזרים תיאורים, נתוני מו"ל ומקור לספרים:
`feat: add seedAllMetadata…`, `fix gemini`, `close connection, skip null`,
`fix: multiline CSV records and explicit Sourcefolder mapping`. base ל-Otzaria PR.

### 4. `default_commentators`
מפרשי ברירת-המחדל לספרים (Otzaria PR #10): פיצול משנה ברורה לספר עצמאי, פערים
מכוונים במיקומי מפרשים, נרמול שמות ב-alt-toc, ו-refactor ל-`DefaultCommentatorPosition`.

### 5. `fix/category-ids-full-path`
תיקון: מזהי קטגוריה לפי נתיב מלא מהשורש (לא לפי שם-עלה), למניעת התנגשות מזהים
(שו"ת שנחתו תחת קבלה/מחברי זמננו). — `fix(otzaria): key category ids by full path`

### 6. `fix/book-corpus-talmud`
עדכון לוגיקת `_book_corpus` לכלול "Talmud" עבור בבלי וירושלמי (+טסט).

### 7. `perf/faster-generation`
שיפורי מהירות: הכנסת רשומות TOC ב-batch בטרנזקציה אחת; הגדרת ריצה ל-tmpfs.

### 8. `word-level-link-anchors`
עוגני-מילה לקישורים — טבלת `link_anchor` לעיגון קישור לטווח תווים בשורה:
word-level anchors, ייבוא charLevelData מדויק, ותוויות תצוגה לעוגני order-only.

### 9. `feat/ranged-links-and-book-versions`
- `feat(links)`: קישורי-טווח החוצים שורות ("Exodus 1:1-6:1") — `link_range`/`link_coverage`.
- `feat(versions)`: אחסון גרסאות/מהדורות ספר — `book_version`/`version_line`.
- `fix(versions)`: flush ב-batch לכל קובץ-גרסה.
> תומכי delta (רשומים ב-`PatchTables` וב-`LogicalContentHasher`).

### 10. `feat/hearot-standalone-books`
קבצי "הערות על &lt;title&gt;" מיובאים כספרים עצמאיים מקושרים (במקום `notesContent`
הישן שאף לקוח לא הציג), ומוגדרים כמפרשי ברירת-מחדל לפי טבלת הקישורים (לא לפי תחיליות
שם). — `feat(otzaria): import 'הערות' companion files as standalone linked commentator books`

### 11. `feat/otzaria-ranged-links-and-alt-toc`
מביא את הגנרטור של **otzariasqlite** לרמת ה-importer של ספריא בשני היבטים
שהיו עד כה בצד ספריא בלבד:
- **קישורי-טווח:** ה-JSON יכול לשאת `line_index_1_end`/`line_index_2_end`
  (שורת-סיום 1-based לכל צד); הקישור נשאר מעוגן בשורת ההתחלה, `link_range`
  שומר את שורת הסוף ו-`link_coverage` מסמן כל שורה מכוסה (כותרות מוחרגות).
  הקישורים נכנסים דרך `insertLinkStable` לפי `(source,target,connectionType)`,
  וטווחים ישנים נמחקים לפני ייבוא-מחדש (סנכרון סמכותי, delta-friendly).
- **מבני alt-toc ("עליות"):** קריאת `alt_toc/<book>_alt_toc.json` (המקבילה
  האוצריאית ל-`alts` של ספריא) → `alt_toc_structure`/`alt_toc_entry`/
  `line_alt_toc`, כולל מיפוי כל שורה לעוגן הקודם הקרוב וסימון ילד-אחרון/יש-ילדים.
  ספרי-ספריא לא נגעים (המבנים שלהם מהסכימה); מבנים/מפתחות שאינם מוכרזים עוד
  נמחקים. — `feat(otzaria): ranged links + alt-toc structures in the otzariasqlite generator`
> תלוי בטבלאות `link_range`/`link_coverage` (פיצ'ר #9) — לכן ממוקם מעליו.
> טסטים: `OtzariaRangedLinksTest`, `OtzariaAltTocTest`.

### `otzaria` — ראש השרשרת
הענף הראשי של הפורק. מכיל את **בועות** כל הפיצ'רים (merge לכל אחד), ומעליהן **רק**
את הקומיטים הספציפיים לאוצריא — רבים מהם "ביטולים" של שלבי ריצה, או סטיות מכוונות
והפיכות מ-upstream: `התאמה לאוצריא`, `delta-updater לא סביב Lucene`, `ביטול catalog.pb`,
`ביטול bundle`, `otzaria כראשי`, `manifest כשאין releases`, `הורדת ספריית אוצריא בלבד`,
`אי-אריזת מודל ההטמעה`, `שינויי מיקומים → תת-קטגוריה`, החרגת/התרת "תא שמע", ועריכת
`books_blacklist`. בראש — `chore: שימור release-manifest.json` + קומיטי manifest של ה-CI.

---

## הוספת פיצ'ר חדש

1. בסס ענף חדש על ראש שכבת הפיצ'רים (מתחת ל-`otzaria`), עם הקומיטים שלו בלבד.
2. הוסף שורה ב-`STACK` וב-`FEATURES` שב-[`scripts/cascade-master.sh`](scripts/cascade-master.sh),
   במקום הנכון (הוא יבוסס על הקודם).
3. הרץ את ה-cascade: הוא יבצע rebase לינארי לשכבת הפיצ'רים, ויבנה מחדש את `otzaria`
   עם בועה חדשה לפיצ'ר + קומיטי-אוצריא מעל. `DRY_RUN=1` לבדיקה בלי push.

> **כלל:** קומיטי `otzaria` תמיד בראש; PR של פיצ'ר ממוזג **מתחת** להם (כבועה).
