# תוכנית ייבוא v2.2: נתוני תלות (isDependant) וסוגי קישורים מ־Sefaria

תאריך: 2026-07-12. גרסה 2.2 — לאחר שלושה סבבי סקירה חיצונית; כל ממצא אומת עצמאית לפני הטמעה. עיקרי סבב 3: מסלול מיידי לתיקון ה־updater (לפני כל עבודת סכמה 2), ‏declared/inferred כהחלטת מוצר שנפתרת בדרגת provenance (לא מחיקת דגלים), סדר rollout אפליקציה־לפני־DB, ‏E2E אמיתי ל־updater, והכרעות בעלים פתוחות מרוכזות בסעיף 12.

**סטטוס: התוכנית מאושרת ומוכנה למימוש — שתי הכרעות הבעלים התקבלו (12.7.2026, סעיף 12).**

## 0. אזהרה חיה — סחף ה־updater (בלתי־תלוי בתוכנית, לתקן מיד)

חמש טבלאות שנוספו ל־`PatchTables.kt`/`LogicalContentHasher` ב־12.7 (`link_anchor`, `link_range`, `link_coverage`, `book_version`, `version_line`; קומיטים `b7e044e`, `fad55ea`, `6ff6c9c`) **חסרות** בעותק המשוכפל של החבילה הדארטית `seforim_library_updater` (‏`otzaria/pubspec.lock` נעול על `951eb98` מ־3.7) — גם ב־`kPatchTablesInFkOrder` וגם ב־`kHashTableOrder`. תוצאה מאומתת: **כל patch מהמפיק הנוכחי יידחה** על־ידי ה־applier ‏(hash mismatch → ‏`PatchApplyException` → ‏rollback). זה fail-safe (אין שחיתות נתונים) אבל נתיב הדלתא שבור כבר עכשיו.

**מסלול מיידי (לפני כל עבודת סכמה 2!)** — כדי שנתיב הדלתא החי לא יישאר שבור לאורך הפיתוח:
1. הוספת חמש הטבלאות בחבילה (רפו מקומי: `/Users/david/Documents/otzaria-software/seforim_library_updater`) — **בלי** שינויי סכמה 2.
2. ‏acceptance test אמיתי בסכמה 1: החלת patch אמיתי מהמפיק הנוכחי על עותק DB אמיתי (להגדיר `SEFORIM_LIBRARY_RELEASES_DIR` — היום 6 בדיקות ה־E2E מדולגות בגללו; ‏59 בדיקות ה־unit לבדן אינן מספיקות). קריטי במיוחד כי האפליקציה רצה עם `verifyFromHash=false` ‏(`library_update_repository.dart:485`) — ‏`toContentHash` אחרי ההחלה הוא השער היחיד.
3. ‏push לחבילה → עדכון `pubspec.lock` באפליקציה → הרצת בדיקות האפליקציה → ‏release אפליקציה.
4. רק אז מתחילים את עבודת סכמה 2. בהמשך push+lock **שני** עבור `book_base_text` + ‏supportedSchemaVersion=2 (סעיף 7).

## 1. הפער (ללא שינוי מ־v1, מאומת)

הנתונים כבר מיוצאים במלואם מ־SefariaExport ואף נקראים בזמן ייבוא — אבל לא נשמרים:
- ‏`dependence`/`base_text_titles`/`collective_title` נקראים ל־`BookMeta` לצורך כיוון קישורים ונזרקים; אין אף עמודת DB.
- ‏Connection Type כבר ממופה ל־enum בן 15 ערכים; 8 סוגים בעלי־שם קורסים ל־OTHER‏ (18,317 שורות: ‏sifrei mitzvot ‏14,071, ‏essay ‏3,466, ‏allusion ‏751, ‏liturgy ‏13, ‏ellucidation ‏12, ‏explication ‏2, ‏law ‏1, ‏summary ‏1). ‏26 ערכים גולמיים לא־ריקים סה"כ (27 עם הריק).
- אין צורך ב־`api/texts` ואין צורך בשינוי כלשהו ב־SefariaExport.

## 2. עובדות שנקבעו באימות (מחייבות את העיצוב)

1. **`ellucidation` הוא קישור תלוי — אבל צפי ה־DB הוא 0**: ‏12/12 השורות הן עין איה 2:2 → שבת; עין איה = ‏Commentary עם `base_text_titles=[Berakhot, Shabbat]`. אולם **עין אי"ה נמצא ב־`books_blacklist.txt` (בשמו העברי) ואינו ב־DB (אומת: 0 שורות)** — כל 12 השורות נופלות בייבוא. ‏ELUCIDATION מקבל טיפול dependent מלא (סעיף 5) כהכנה לעתיד/להסרה מה־blacklist, וה־baseline ב־QA הוא **0**.
2. **באג round-trip**: כל קריאת DB→מודל עוברת `ConnectionType.fromString(ct.name)` ‏(`ModelExtensions.kt:337,353,371,404`); ‏enum ששמו אינו ענף ב־`fromString` יידרדר בשקט ל־OTHER בקריאה. חובה ענף לכל שם enum + בדיקת round-trip.
3. **מזהי `connection_type` מצומדים לסדר ההכרזה** בבנייה־מאפס (‏allocator לפי סדר הזריעה; ‏`Link.kt:79-82` מתעד את הכלל). ערכים חדשים — **רק אחרי LINKER** (ids ‏16–23). הערה: ‏`assertNoSecondaryUniqueCollisions` תופס שינוי סדר רק בייצור patch מול DB קיים; בבנייה־מאפס טהורה שום דבר לא תופס — ולכן נדרשת בדיקת יציבות מפורשת (סעיף 3.3). בנוסף, הבדיקה הקיימת `GenerateLinkerLinksTest.kt:81` ‏(`values().last()==LINKER`) **תישבר** ותוחלף.
4. **הערך הגולמי של `dependence` אובד**: ‏`extractDependence` ממפה לא־מוכר→`OTHER_DEPENDANT` ‏(`SefariaBookPayloadReader.kt:252-262`); "Guides" לא ניתן לשחזור מהמבנה הקיים. נדרש `rawDependence` ב־payload.
5. **baseline תלות = 4,941** בהתאמת `heRef` (‏Commentary ‏4,889, ‏Targum ‏45, ‏Midrash ‏5, ‏Guides ‏2). ההתאמה לפי `title` נותנת 4,935 בגלל בדיוק 6 ספרים ששמם שונה בייבוא (ר' סעדיה גאון על בראשית/עזרא/נחמיה, אדרת אליהו, חומת אנך על דה"י א/ב).
6. **מקור הקיבוץ = `schemas/*.json` בלבד**: ‏52 מקרי "TOC בלבד" הם fallback של ספריא (‏52/52: ‏`collectiveTitle==title`, ‏`heCollectiveTitle==heTitle`) — לא מטא־דאטה. ב־TOC אלו מחרוזות שטוחות; בסכמה — ‏dict ‏en/he‏ (5,474 ספרים, ‏20/20 במדגם תואמים 1:1 ל־TOC).
7. **`base_text_order` = סדר ספר־הבסיס בקורפוס** (נבדק על מפרשים בעלי ספר־בסיס יחיד: ‏427/427 בסיסים עם ערך יחיד לכל פרשניהם; מפרשי בראשית=1, תהלים=27), לא סדר מפרשים; ‏58 ערכים מקודדים float. **לא נשמר בשלב זה** — אין צרכן מוצדק.
8. **ערבוב declared/inferred הוא התנהגות מכוונת עם שם מטעה — החלטת מוצר, לא רק באג**: כשאין `base_text_titles` אך יש `dependence`, מוסק בסיס מ־"X on Y" ‏(`SefariaBookPayloadReader.kt:143-147`) והתוצאה נכתבת **גם** ל־`sefariaDeclaredBaseTextBookIds` (‏`SefariaDirectImporter.kt:452-455`), ומשפיעה על `link.isDeclaredBase` המשמש כמפתח מיון ב־9 שאילתות ה־mirror ‏(`ORDER BY l.isDeclaredBase DESC`). ההתנהגות נוספה בכוונה בקומיט `521838d` ‏("improve source ranking...") — נחלת אבות מוזכרת מפורשות כדוגמה רצויה ב־4 מקומות בקוד (‏`Link.kt:42`, ‏`SefariaLinksImporter.kt:287`, ‏`SefariaImportModels.kt:37`, ‏`SefariaBookPayloadReader.kt:139`). לכן **אסור פשוט לאפס את 13,056 הדגלים** — זה יבטל ranking מכוון. הפתרון: דרגת provenance (סעיף 4.2) שמשמרת את ה־boost ומדייקת את המקור. מספרי ייחוס: ‏5,446 זוגות סה"כ = ‏5,426 מוצהרים + ‏20 מוסקים (שחזור מדויק מחייב רזולוציית alt-titles; שרשרת פשוטה נותנת 5,420/18).
9. **‏Citation 1 ≠ מקור**: ‏Sefaria ממיין את `refs` **אלפביתית** בשמירה (‏`link.py:101` ‏`_pre_save`, כולל היפוך המערכים המקבילים), גם בקישורי commentary; ה־exporter מעתיק כמות־שהוא. השערת המייל הופרכה. כיוון קישורים תלויים ממשיך להיקבע אך ורק מ־`dependence`/`base_text_titles` (המנגנון הקיים) — אסור להסתמך על סדר העמודות.
10. **‏rollout חוצה־סכמה בלתי־אפשרי טכנית**: ‏`PatchDbProducer.scanUpserts` נכשל קשה (`no such column: prev.<col>`) כשל־prev חסרות עמודות; ‏`PatchPipelineCli.kt:121-122` כותב ל־manifest ‏`fromSchemaVersion/toSchemaVersion` מ־system properties עם ברירת מחדל קשיחה `"1"`, בלי לקרוא מה־DB.
11. **‏task graph של ה־release**: ‏`generateSeforimDb` לא בונה `catalog.pb` ולא אינדקסי Lucene; ‏`packageSeforimBundle` תלוי רק ב־`packageArtifacts` (ה־dependsOn על generateSeforimDb מוער!); ‏`packageArtifacts` מזהיר־וממשיך כש־`catalog.pb` חסר; אינדקסי Lucene לעולם אינם נארזים ל־bundle.

## 3. סכמה — גרסה 2

### 3.1 עמודות חדשות ב־`book` (‏`Database.sq:143-167`)
```sql
dependenceType TEXT DEFAULT NULL,     -- הערך הגולמי מנורמל-case מ-schema: 'Commentary'|'Targum'|'Midrash'|'Guides';
                                      -- ערך עתידי לא-מוכר נשמר כלשונו (trim בלבד) - לא נכפה ל-enum סגור
collectiveTitleHe TEXT DEFAULT NULL,  -- schema collective_title.he (קיבוץ מפורש בלבד; NULL כשאין)
collectiveTitleEn TEXT DEFAULT NULL   -- schema collective_title.en
```
- ‏`isDependant` נגזר: ‏`dependenceType IS NOT NULL`. אין עמודה בוליאנית.
- **אין** `baseTextOrder` (עובדה 7).
- תצוגה באפליקציה בעתיד: ‏`COALESCE(collectiveTitleHe, title)`.

### 3.2 טבלה חדשה `book_base_text` — יחסים **מוצהרים בלבד**
```sql
CREATE TABLE IF NOT EXISTS book_base_text (
    bookId INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
    baseBookId INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
    PRIMARY KEY (bookId, baseBookId)
);
CREATE INDEX IF NOT EXISTS idx_book_base_text_base ON book_base_text(baseBookId);
```

### 3.3 ‏8 ערכי `ConnectionType` — **אחרי LINKER** (עובדה 3; ids ‏16–23)
`SIFREI_MITZVOT, ESSAY, ALLUSION, LITURGY, ELUCIDATION, EXPLICATION, LAW, SUMMARY`
- ‏`fromString` (ה־API הציבורי, ‏fallback ל־OTHER נשמר): ענפים לערכים הגולמיים **וגם** לשמות ה־enum — בפרט `ellucidation` (כתיב ספריא) **וגם** `elucidation` (round-trip, עובדה 2). שאר השמות מנורמלים לעצמם ממילא.
- ‏**API קפדני נפרד** `ConnectionType.fromKnownStringOrNull(raw)`: מחזיר `null` לערך לא־מוכר (במקום OTHER). זהו הבסיס ל־guard ביבואן (סעיף 4.3) — בלי לסתור מיפוי מפורש של `"other"`/`"none"` ל־OTHER, ובלי לשבור את הצרכנים הקיימים של `fromString`.
- שורות `connection_type` נזרעות אוטומטית מ־`ConnectionType.values()` — אין זריעה ידנית.
- **החלפת בדיקת ה־enum הקיימת**: ‏`GenerateLinkerLinksTest.kt:81` ‏(`assertEquals(ConnectionType.LINKER, values().last())`) תישבר בכוונה — תוחלף בבדיקת יציבות append-only: ‏`assertEquals(14, ConnectionType.LINKER.ordinal)` + אימות שכל הערכים החדשים אחריו (ו־`fromString(v.name)==v` לכל ערך).

### 3.4 שכבת DAO/מודלים (פערים שזוהו בסקירה)
- שדות חדשים ב־`core.models.Book` + עדכון `ModelExtensions` + **שני** מסלולי ה־insert‏ (`SeforimRepository.kt:908-924, 968-983`).
- ‏`BookBaseTextQueries.sq` חדש + ‏API קריאה: בסיסים של ספר, תלויים של ספר.
- בדיקות SQLDelight: ‏FK, ‏cascade (מחיקת ספר גוררת מחיקת שורות הגשר).

## 4. שינויי קוד ייבוא

### 4.1 ‏`SefariaBookPayloadReader.kt` + ‏`SefariaImportModels.kt`
- להעביר `rawDependence: String?` (הערך הגולמי אחרי trim ונירמול case קנוני) לצד ה־enum הפנימי (עובדה 4).
- **להוסיף ל־`BookPayload` את `collectiveTitleHe` ו־`collectiveTitleEn`**: הקורא היום מחלץ רק את `collective_title.en` ‏(`:148-149`) — יש להרחיב לחילוץ `.he` ולהעביר את שניהם דרך ה־payload עד ה־insert.
- **לפצל** `declaredBaseTextTitleKeys` / `inferredBaseTextTitleKeys` (עובדה 8): ההסקה מ־"X on Y" ‏(`:143-147`) עוברת לשדה הנפרד.

### 4.2 ‏`SefariaDirectImporter.kt` + דרגת provenance ב־`link`
- כתיבת `dependenceType`, ‏`collectiveTitleHe/En` (מ־`collective_title` של הסכמה, לא מה־TOC) בעת insert הספר.
- המעבר הדחוי (‏`:436-490`): רזולוציה לשני הסטים בנפרד; **כיוון קישורים ממשיך להשתמש באיחוד** (declared ∪ inferred — שימור התנהגות הכיוון הקיימת).
- **החלפת `link.isDeclaredBase` בדרגת provenance** (מותרת בזכות השבירה החד־פעמית): ‏`baseProvenance INTEGER NOT NULL DEFAULT 0` — ‏`0=NONE`, ‏`1=INFERRED_TITLE`, ‏`2=SEFARIA_DECLARED`. כך:
  - ה־**ranking המכוון נשמר ואף מתדייק**: ‏9 שאילתות ה־mirror עוברות מ־`ORDER BY l.isDeclaredBase DESC` ל־`ORDER BY l.baseProvenance DESC` — מוצהר מעל מוסק מעל כלום. הסדר הרצוי מוגדר כאן **מראש** (לא רק regression בדיעבד): נחלת אבות (מוסק) נשאר מעל קישורים ללא בסיס, ומתחת למוצהרים.
  - ה־provenance אמיתי: אין יותר מוסק שמתחזה למוצהר.
  - נקודות עדכון: הסכמה (`Database.sq:329`), שני מסלולי insert ‏(`LinkQueries.sq:134,138`), ‏9 ה־ORDER BY‏ (203,222,242,263,285,307,318,328,342), ‏ModelExtensions, וה־mirror של האפליקציה אם הוא כולל את העמודה.
- ‏`book_base_text` — ‏declared בלבד (‏baseline ‏5,426). **אין לערבב נאמנות מטא־דאטה עם ranking של UI** — הקיבוץ והיחסים מהטבלה, ה־boost מ־`baseProvenance`.

### 4.3 ‏guard קשיח ביבואן הקישורים — דרך `fromKnownStringOrNull`
היבואן ממפה עם `fromKnownStringOrNull(raw)`; ‏`null` (ערך לא־מוכר) = **כשל build** עם הערך שנתקל בו. ‏`""`/`"none"`/`"other"` ממופים מפורשות ל־OTHER ולכן עוברים. סוג חדש עתידי של ספריא יתגלה בקול ולא ידרדר בשקט (מדיניות no-fallbacks). שימו לב: בדיקת round-trip **אינה** מכסה את זה (גם ערך לא־מוכר חוזר היום כ־OTHER) — לכן ה־API הנפרד, לא בדיקה על `fromString`.

## 5. ‏ELUCIDATION כסוג תלוי — כל נקודות המגע (מאומת file:line)

בתוך SeforimLibrary:
1. ‏`ORIENTED_DEPENDANT_TYPES` — ‏`SefariaLinksImporter.kt:781-798`.
2. ‏9 רשימות ה־mirror ב־`dao/LinkQueries.sq` — שורות **201, 220, 240, 261, 282, 304, 316, 326, 339**.
3. רשימת `hasSourceConnection` — ‏`SefariaLinksImporter.kt:630-633`.
4. רשימת ה־demotion חוצת־קורפוס — ‏`:500-502` (על נתוני היום זה no-op — כל 12 הקישורים תוך־ש"ס — אבל נדרש לעקביות).
5. ‏`LinkQueries.sq:126` ‏(`selectCommentatorsByBook`, היום `('COMMENTARY','TARGUM')`) — **ליישר לקבוצת התלויים המלאה** ‏(`COMMENTARY, SUPER_COMMENTARY, TARGUM, MIDRASH, PARSHANUT, DIBUR_HAMATCHIL, ELUCIDATION`) ולא רק להוסיף ELUCIDATION — אחרת הסוג החדש נתמך טוב יותר מסוגים ותיקים. **זהו שינוי התנהגות** לצרכנים הקיימים (`SeforimRepository.kt:1772, 2087` — רשימות מפרשים יתרחבו ב־MIDRASH/PARSHANUT וכו') — קומיט נפרד ומסומן, הכרעת בעלים.

באפליקציה (otzaria):
6. ‏`LinkTypes.dependentTextTypes` — ‏`lib/models/link_types.dart:22-29` (+ קבוע שם חדש).
7. שתי רשימות ה־IN המקודדות — ‏`lib/migration/database/sql/LinkQueries.sq:35,59`.

שאר 7 הסוגים החדשים נשארים reference-like — מאומת ש־`essay` כולל קישורים של ספר תלוי **שלא** מול הבסיס שלו (למשל ‏Steinsaltz Introductions ↔ Steinsaltz on Psalms), ולכן אינו dependent.

## 6. גרסת סכמה ו־rollout (עובדה 10)

1. ‏`PatchDbSchema.CURRENT_VERSION` → ‏2; עדכון `schema_meta.db_schema_version` → ‏2 (ו־`db_version` לפי הנוהג).
2. ‏`PatchPipelineCli`: לקרוא `fromSchemaVersion`/`toSchemaVersion` מ־`schema_meta` של שני ה־DBs (או לחייב properties מפורשים ולהיכשל בהיעדרם) — לבטל את ברירת המחדל הקשיחה `"1"` ב־`:121-122`.
3. **אין לייצר patch מ־v15 לסכמה 2** (המפיק ממילא נכשל קשה). השחרור הראשון בסכמה 2 = **full download בלבד**.
4. אחרי ה־full: דלתא סינתטית schema 2 → schema 2, נבדקת דרך ה־updater **האמיתי** של האפליקציה (לא רק ה־applier הקוטליני).

### 6.1 סדר release כולל — אפליקציה לפני DB (חוסם)
אפליקציה ישנה (updater בסכמה 1) תוכל להוריד full DB בסכמה 2 ולקרוא אותו — אבל **כל דלתא 2→2 עתידית תידחה אצלה**. לכן:
1. תיקון הדלתא החי: ‏updater עם 33 טבלאות → ‏app lock → ‏release אפליקציה (המסלול המיידי מסעיף 0).
2. פיתוח סכמה 2 בכל המאגרים.
3. ‏updater עם 34 טבלאות + ‏supportedSchemaVersion=2 → ‏app lock → **release אפליקציה**.
4. **רק אז** פרסום full DB בסכמה 2.
5. לאחר מכן דלתאות 2→2.

### 6.2 שימור lineage של ה־buildstate
בבנייה המלאה של סכמה 2 יש לבנות **עם** `seforim.db.buildstate` הקיים (לא מאפס) — כדי לא לשנות מזהים קיימים (ספרים, קטגוריות, connection_type ‏1–15) שלא לצורך. הערכים החדשים (16–23, טבלת book_base_text) מוקצים בהמשך המונים הקיימים.

## 7. המאגר הרביעי — חבילת העדכונים (חובה, כולל תיקון הסחף החי מסעיף 0)

**מיקום העבודה המקומי: `/Users/david/Documents/otzaria-software/seforim_library_updater`** ‏(repo: ‏Otzaria/otzaria_library_updater). מצב מאומת: הרפו המקומי מקדים את ה־checkout הנעול באפליקציה (קומיטים חדשים: booksTouched, כלי אבחון hash), אבל `kPatchTablesInFkOrder`/`kHashTableOrder` עדיין בני **28 טבלאות** — חמש הטבלאות חסרות **גם בו** — ו־`supportedSchemaVersion=1` ‏(`patch_applier.dart:88`).

שלבי התיקון בחבילה:
1. סנכרון `lib/src/models/patch_table_spec.dart` (שתי הרשימות) לרשימה הקוטלינית המלאה: ‏33 הטבלאות הקיימות (כולל `link_anchor`, `link_range`, `link_coverage`, `book_version`, `version_line` באותם מיקומים) + ‏`book_base_text` = ‏34, באותו סדר ועם אותם דגלי updatable ו־PK.
2. ‏`supportedSchemaVersion` → ‏2 ‏(`lib/src/services/patch_applier.dart:88`), ו**הכרעה חד־משמעית ב־`kBooksTouchedTables`: ‏`book_base_text` נכללת, עם איסוף שני הצדדים (`bookId` וגם `baseBookId`)** — שניהם מזהי ספרים ששינוי בהם נוגע לספר. (האפליקציה כרגע זורקת את `PatchApplyResult` ולכן `booksTouched` עוד לא נצרך שם — אבל החוזה חייב להיות שלם מראש.)
3. ‏commit+push לרפו החבילה, ואז עדכון `resolved-ref` ב־`otzaria/pubspec.lock` (`dart pub upgrade seforim_library_updater`).
4. **בדיקת contract בין המאגרים** כדי שהרשימות לא יסטו שוב: ‏CI שמשווה טבלאות/סדר/דגלים/גרסה בין `PatchTables.kt` לבין `patch_table_spec.dart` (למשל fixture JSON מיוצר משני הצדדים).

## 8. תהליך release — רצף מפורש (עובדה 11 + הכרעת בעלים)

**הכרעת בעלים (12.7.2026): ‏`catalog.pb` ואינדקסי ה־Lucene אינם בשימוש והוסרו במכוון.** לכן:
- הרצף הנדרש פשוט: ‏`generateSeforimDb` → ‏`:packaging:packageArtifacts` / ‏`packageSeforimBundle`.
- אזהרת "‏catalog.pb חסר — מדלג" ב־`PackageArtifacts.kt:69-71` היא ההתנהגות הנכונה (אין להפוך לכשל).
- מומלץ (לא חוסם): לסמן/לנקות את משימות `:catalog:buildCatalog` ו־`:searchindex:buildLuceneIndexDefault` והפניות `mustRunAfter` אליהן כלא־בשימוש, כדי שלא יטעו סוקרים עתידיים.

## 9. תוכנית קומיטים

| # | קומיט | תוכן | תלוי ב־ |
|---|---|---|---|
| 1 | **המסלול המיידי** (סעיף 0): סנכרון 5 הטבלאות + ‏E2E אמיתי בסכמה 1 + ‏push + ‏app lock + ‏release אפליקציה | מתקן את הסחף החי **לפני** כל עבודת סכמה 2 | — |
| 2 | schema v2: book columns + book_base_text | ‏3.1–3.2 + ‏PatchTables + ‏Hasher + ‏CURRENT_VERSION=2 | — |
| 3 | enum: 8 סוגים אחרי LINKER + ‏fromKnownStringOrNull + בדיקות | ‏3.3 (כולל החלפת בדיקת `values().last()` באסרטות ordinal + ‏round-trip) | — |
| 4 | DAO/מודלים/API: ‏Book fields, ‏ModelExtensions, שני ה־inserts, ‏BookBaseTextQueries.sq | ‏3.4 | 2 |
| 5 | ELUCIDATION dependent (צד הספרייה) | סעיף 5 פריטים 1–4 | 3 |
| 6 | import: rawDependence + ‏collectiveTitleHe/En ב־payload + פיצול declared/inferred + התמדה | ‏4.1–4.2 | 2,4 |
| 7 | strict guard על סוג לא־ממופה | ‏4.3 | 3 |
| 8 | schema-version plumbing ב־CLI/manifest | ‏6.2 | 2 |
| 9 | ‏selectCommentatorsByBook: יישור לקבוצה המלאה (שינוי התנהגות, הכרעת בעלים) | סעיף 5 פריט 5 | 5 |
| 10 | updater: ‏book_base_text + ‏supportedSchemaVersion=2 + ‏contract test + עדכון pubspec.lock באפליקציה | סעיף 7 שלבים 1–4 (השלמה) | 1,2 |
| 11 | אפליקציה: ELUCIDATION ‏(dependentTextTypes + ‏.sq:35,59) | סעיף 5 פריטים 6–7 | 5 |
| 12 | כלי QA versioned במאגר (סעיף 10א) | סקריפטי הייחוס נכנסים ל־repo | — |
| 13 | full rebuild + ‏QA (סעיף 10) + דלתא סינתטית 2→2 דרך ה־updater האמיתי | — | הכל |

הערת סדר (תוקנה מ־v2): התמדת הייבוא (קומיט 6) חייבת לבוא **אחרי** שכבת ה־DAO/מודלים (קומיט 4) — ‏insert של עמודות חדשות דורש את `Book`/`SeforimRepository` המעודכנים.

## 10א. שחזוריות ה־QA (תנאי מוקדם)

- **סקריפטי הייחוס נכנסים למאגר** (למשל `scripts/qa/`) כקוד versioned — היום הם קיימים רק ב־scratchpad של סשן (claim_a..f.py) ואינם ניתנים לשחזור. קומיט 12.
- **המספרים אינם קבועים נצחיים**: הבילדר צורך release של SefariaExport וה־exporter משכפל את Sefaria-Project ‏HEAD ללא הצמדה (`05_clone_sefaria_project.sh` — ‏`git clone --depth 1`). ‏4,941/5,426/13,056 נכונים ל־snapshot המקומי הנוכחי. לכן אחת מהשתיים: (א) הצמדת קלטי הבנייה (tag של release + ‏SHA של Sefaria-Project); או (ב) **חישוב ה־expected מהארטיפקטים של אותה בנייה עצמה** והשוואה row-by-row — זו ברירת המחדל של סקריפטי ה־QA. הערכים הקשיחים למטה תקפים כל עוד בונים מה־snapshot הנוכחי.

## 10. ‏QA קשיח (ערכי ייחוס מאומתים ל־snapshot הנוכחי)

1. ‏`SELECT COUNT(*) FROM book WHERE dependenceType IS NOT NULL` **= 4,941** (התאמה ב־pipeline; אימות חיצוני לפי `heRef`). פילוח: ‏Commentary ‏4,889, ‏Targum ‏45, ‏Midrash ‏5, ‏Guides ‏2.
2. ‏`SELECT COUNT(*) FROM book_base_text` **= 5,426** (מוצהרים בלבד; סקריפט האימות חייב רזולוציית alt-titles — בלעדיה מתקבל 5,420 והשוואה תיכשל כזובה).
3. ‏ELUCIDATION: **0 קישורים ב־DB** (עין אי"ה ב־blacklist; ‏12 שורות ה־CSV נופלות בייבוא). אם עין אי"ה יוסר מה־blacklist: ‏12 קישורים עין איה↔שבת, מכוונים base→dependant, ‏`baseProvenance=2` ‏(SEFARIA_DECLARED).
4. בדיקות enum: ‏round-trip ‏`fromString(v.name)==v` לכל ערך; ‏`LINKER.ordinal==14` וכל החדשים אחריו; ‏`fromKnownStringOrNull` מחזיר null לערך לא־מוכר ו־OTHER ל־`""`/`"none"`/`"other"`.
5. **מוני importer לפי סוג** (מחליף את בדיקת "‏DB ≤ CSV" — שאינה invariant: פיצול fromRefs×toRefs והרחבת טווחים מייצרים כמה רשומות משורת CSV אחת; ה־DB הנוכחי כבר מדגים TARGUM ‏34,729 מול ‏34,727 ב־CSV): לכל סוג לדווח — שורות CSV שנקראו, שורות שנפלו (ref/ספר חסר), זוגות refs שנפתרו, רשומות link שנכתבו. **ההשוואה מול ה־DB נעשית מיד בסוף שלב ייבוא הקישורים של ספריא** (או עם תיוג לפי source) — ב־DB הסופי גם ‏havrouta/linker/otzaria מוסיפים קישורים. ‏OTHER צפוי לרדת בסדר גודל של ~18K בלבד (הריקים נשארים OTHER אלא אם שוחזרו במנגנון הקיים).
6. השוואת מטא־דאטה **שורה־שורה לפי `heRef`** מול הסכמות (לא רק ספירות): ‏dependenceType, ‏collectiveTitleHe/En.
7. דרגת provenance — ‏baselines קשיחים (אומתו מול ה־DB הנוכחי): **13,056 קישורים מקבלים `baseProvenance=1` ‏(INFERRED_TITLE)** — ‏12,829 ‏COMMENTARY + ‏227 ‏REFERENCE, על בדיוק **20 זוגות מוסקים** (הגדול: ‏Levushei Serad→שו"ע או"ח, ‏4,003); שאר הקישורים שהיו `isDeclaredBase=1` מקבלים `2` ‏(SEFARIA_DECLARED). אפס זוגות חוצי־קורפוס ⇒ ‏demotion לא משתנה. בדיקת סדר תצוגת SOURCE מאמתת את הסדר **שהוגדר מראש** בסעיף 4.2 (מוצהר > מוסק > ללא), לא רק היעדר שינוי.
8. ‏`PRAGMA quick_check` + ‏`PRAGMA foreign_key_check`.
9. ‏build נכשל על סוג לא־ממופה לא־ריק (בדיקה עם CSV סינתטי).
10. ‏full download באפליקציה + החלת patch ‏2→2 סינתטי דרך ה־updater המעודכן באפליקציה. בדיקות ה־updater חייבות לכלול את מסלול ה־E2E האמיתי (`SEFORIM_LIBRARY_RELEASES_DIR` מוגדר; היום 6 בדיקות E2E מדולגות ורק 59 unit רצות) — קריטי כי האפליקציה רצה `verifyFromHash=false` וה־`toContentHash` שאחרי ההחלה הוא השער היחיד.

## 11. מחוץ לתחולה

- ‏`baseTextOrder` — נדחה (סמנטיקה: סדר קורפוס של ספר הבסיס; אם יידרש בעתיד: שם `baseCorpusOrder`, טיפוס REAL, מקור TOC).
- הפניות סימטריות לקישורי reference-like (היום נשמרים בכיוון האלפביתי של ספריא והאפליקציה קוראת כיוון הפוך רק ל־dependent types) — שאלת מוצר נפרדת; דורשת שינוי בשאילתות הקריאה, לא בייבוא.
- שיפור שחזור 2.03M הריקים; ‏`base_text_mapping`; ‏`is_cited`; קיבוץ מפרשים ב־UI לפי `collectiveTitleHe` (המשך טבעי אחרי הייבוא, במאגר האפליקציה).
- ‏**ESSAY נשמר בשם בלבד**: השדות הייחודיים של קישורי essay בספריא (`versions`, ‏`displayedText`) אינם מיוצאים כלל (ה־CSV הוא 9 עמודות) — מתועד כמגבלה ידועה, לא כפער ייבוא.

## 12. הכרעות בעלים — **התקבלו (12.7.2026)**

1. **יישור `selectCommentatorsByBook`** ‏(`dao/LinkQueries.sq:126`) לקבוצת התלויים המלאה — **אושר**. מבוצע כקומיט 9, מסומן כשינוי התנהגות.
2. **אריזת release** — ‏`catalog.pb` ואינדקסי Lucene **אינם בשימוש והוסרו במכוון**; אין להקשיח את האריזה סביבם. סעיף 8 עודכן בהתאם (רצף מקוצר + המלצת ניקוי לא־חוסמת).

## נספח: מה השתנה בין גרסאות

v1 → v2:
- מקור collectiveTitle: ‏TOC → **schemas** (ה־TOC הוא fallback תצוגתי); שמות עמודות: ‏`collectiveTitleHe/En`.
- ‏baseline: ‏4,935 → **4,941** (התאמת heRef).
- ‏`baseTextOrder` הוסר מהתוכנית (סמנטיקה שגויה ב־v1).
- ‏ELUCIDATION הועבר מ־reference-like ל־**dependent** על כל נקודות המגע, כולל באפליקציה.
- נוספו: פיצול declared/inferred (כולל תיקון באג קיים), ‏rawDependence, ‏guard קשיח, המאגר הרביעי (updater) + contract test, ‏plumbing גרסת סכמה, רצף release מפורש, ‏rollout ללא patch חוצה־סכמה.
- הופרכה סופית השערת "Citation 1 = מקור" (המיון אלפביתי; ‏`link.py:101`).

v2 → v2.1 (סבב סקירה שני, כל הממצאים אומתו):
- ‏baseline ‏ELUCIDATION ב־DB תוקן ל־**0** — עין אי"ה ב־`books_blacklist.txt` (אומת: ‏0 שורות ב־DB); ‏12 שורות ה־CSV מוכיחות סמנטיקה בלבד.
- בדיקת "‏DB ≤ CSV" הוחלפה ב**מוני importer לפי סוג** (‏TARGUM ‏34,729>‏34,727 מפריך את ה־invariant).
- ה־guard מומש כ־**`fromKnownStringOrNull`** נפרד (round-trip לא מגלה ערך לא־מוכר; ‏fromString הציבורי נשאר עם fallback).
- סדר קומיטים תוקן לבר־בנייה (DAO לפני התמדת ייבוא); ‏`collectiveTitleHe/En` ב־`BookPayload` נוסף מפורשות.
- השפעת תיקון declared/inferred כומתה ואומתה: **13,056 מעברי ‏1→0** (‏12,829 COMMENTARY + ‏227 REFERENCE, ‏20 זוגות, אפס חוצי־קורפוס) — baseline קשיח + regression לסדר SOURCE.
- ‏`selectCommentatorsByBook` — יישור לקבוצה המלאה (שינוי התנהגות מסומן) במקום תוספת נקודתית.
- בדיקת `values().last()==LINKER` ‏(`GenerateLinkerLinksTest.kt:81`) מוחלפת באסרטות יציבות ordinal.
- נוספו: סעיף שחזוריות (סקריפטי QA נכנסים למאגר; ‏expected מחושב מארטיפקטי אותה בנייה; קלטים לא מוצמדים — ‏clone לא מוצמד אומת), סייג single-base ל־`base_text_order`, ושלב מפורש לחבילת העדכונים ברפו המקומי `/Users/david/Documents/otzaria-software/seforim_library_updater` (אומת: ‏28 טבלאות, בלי החמש, ‏supportedSchemaVersion=1, מקדים את ה־ref הנעול).

v2.1 → v2.2 (סבב סקירה שלישי; הטענות העובדתיות אומתו):
- **מסלול מיידי** לתיקון ה־updater (סעיף 0): סנכרון + ‏E2E אמיתי בסכמה 1 + ‏push + ‏app lock + ‏release — **לפני** תחילת עבודת סכמה 2, כדי שנתיב הדלתא לא יישאר שבור לאורך הפיתוח.
- ‏declared/inferred סווג מחדש כ**החלטת מוצר**: אומת שהערבוב נוסף בכוונה בקומיט `521838d` (נחלת אבות מתועדת כדוגמה רצויה ב־4 מקומות). הפתרון: ‏`baseProvenance` תלת־ערכי (0/1/2) במקום `isDeclaredBase` — משמר את ה־ranking, מדייק את ה־provenance; ‏book_base_text נשאר declared בלבד; הסדר הרצוי הוגדר מראש.
- הוכרע `kBooksTouchedTables`: ‏book_base_text נכללת עם איסוף שני הצדדים.
- נוסף סדר release אפליקציה־לפני־DB (6.1) + שימור lineage של buildstate (6.2).
- ‏E2E אמיתי ל־updater ‏(`SEFORIM_LIBRARY_RELEASES_DIR`; אומת: 6 בדיקות E2E מדולגות היום, והאפליקציה רצה `verifyFromHash=false` — ‏`library_update_repository.dart:485`).
- מוני ה־importer תוחמו לסוף שלב ספריא (לפני generators אחרים); תועדה מגבלת ESSAY ‏(versions/displayedText לא מיוצאים); ההכרעות הפתוחות רוכזו בסעיף 12.
