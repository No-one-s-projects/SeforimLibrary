# תיאורי קטגוריות — הכנסה ל־DB והצגה באפליקציה

המטרה היא לשמור את `heShortDesc` ואת `heDesc` של קטגוריות ספריא בטבלת `category`, לאפשר עריכה או מחיקה מבוקרת שלהם דרך קובץ ב־`ForDB`, ולהציג אותם בכרטיס הקטגוריה באפליקציה.

חוזה הנתונים של התיקון:

- `category.heShortDesc` ו־`category.heDesc` ב־`seforim.db` הם מקור האמת היחיד לתיאורי קטגוריות.
- האפליקציה אינה קוראת תיאורי קטגוריות מ־`metadata.json` ואינה עוברת למקור נתונים אחר כאשר עמודה או ערך חסרים.
- ערך חסר נשמר כ־`NULL` ב־DB ומומר ל־`''` רק בגבול שבין מודל ה־DB למודל התצוגה של האפליקציה.
- מפתחות ההתאמה הם נתיבי קטגוריה מלאים ומנורמלים, לא כותרת העלה בלבד.

יש להשלים ולאמת את חלק 1 לפני שמתחילים את חלק 2.

## חלק 1 — הכנסת המידע ל־DB (`SeforimLibrary`)

### 1.1 שינוי הסכימה

בקובץ `dao/src/commonMain/sqldelight/io/github/kdroidfilter/seforimlibrary/db/Database.sq`, הוסף בסוף טבלת `category` שתי עמודות nullable:

```sql
CREATE TABLE IF NOT EXISTS category (
    id INTEGER PRIMARY KEY NOT NULL,
    parentId INTEGER,
    title TEXT NOT NULL,
    level INTEGER NOT NULL DEFAULT 0,
    orderIndex INTEGER NOT NULL DEFAULT 999,
    heShortDesc TEXT DEFAULT NULL,
    heDesc TEXT DEFAULT NULL,
    FOREIGN KEY (parentId) REFERENCES category(id) ON DELETE CASCADE
);
```

העמודות הן חלק מסכימת DB מספר 2:

- ה־release האחרון שפורסם בענף `otzaria` עדיין משתמש בסכימה 1.
- `PatchDbSchema.CURRENT_VERSION` כבר שווה `2`; אין להעלות אותו שוב עבור התיקון הזה.
- קומיט `151b9a9` כבר קיים בהיסטוריה. אין לשנות או לשכתב אותו. יש ליצור קומיט חדש, לפני הפרסום הראשון של סכימה 2, ובו להוסיף את עמודות התיאור לאותה סכימה.
- ה־generator בונה DB חדש מאפס, לכן אין להוסיף migration ל־SQLDelight.
- אין patch בין סכימה 1 לסכימה 2. תהליך ה־release הקיים מפרסם DB מלא ומדלג על יצירת patch מול DB בסכימה אחרת; אין להוסיף לוגיקת release חדשה.

### 1.2 עדכון המודלים, השאילתות וה־repository

בצע את השינויים בסדר הבא, כדי שכל שכבה תתקמפל מול השכבה שמתחתיה.

#### א. נרמול נתיבי קטגוריה משותף

שכבת `dao` אינה תלויה במודול `sefariasqlite`, ולכן אסור לה לקרוא ישירות את `sanitizeFolder` שמוגדר בו. צור utility משותף ב־`core`:

`core/src/commonMain/kotlin/io/github/kdroidfilter/seforimlibrary/core/text/CategoryPathNormalization.kt`

```kotlin
package io.github.kdroidfilter.seforimlibrary.core.text

/** Normalizes one category-path segment for stable DB and CSV matching. */
fun normalizeCategoryPathSegment(value: String): String =
    value.replace("\"", "״").trim()

/** Joins normalized category segments using the canonical slash separator. */
fun normalizeCategoryPath(segments: List<String>): String =
    segments.map(::normalizeCategoryPathSegment).joinToString("/")
```

לאחר מכן שנה את `sanitizeFolder` הקיים ב־`SefariaImportText.kt` כך שיטפל ב־`null`/blank כבעבר, ובמקרה האחר יקרא `normalizeCategoryPathSegment`. כך כל הקוד הקיים ממשיך להשתמש ב־API שלו, אבל יש מימוש קנוני יחיד לנרמול.

ה־repository וה־CSV parser ישתמשו ב־utility של `core`; אין להעתיק את פעולת ה־`replace` למספר קבצים.

#### ב. מודל הליבה

בקובץ `core/src/commonMain/kotlin/io/github/kdroidfilter/seforimlibrary/core/models/Category.kt`, הוסף בסוף ה־data class, עם ערכי ברירת מחדל:

```kotlin
val heShortDesc: String? = null,
val heDesc: String? = null,
```

עדכן גם את ה־KDoc של המחלקה.

#### ג. שאילתות SQLDelight

בקובץ `dao/src/commonMain/sqldelight/io/github/kdroidfilter/seforimlibrary/db/CategoryQueries.sq`:

```sql
insert:
INSERT INTO category (parentId, title, level, orderIndex, heShortDesc, heDesc)
VALUES (?, ?, ?, ?, ?, ?);

insertWithId:
INSERT OR IGNORE INTO category (
    id, parentId, title, level, orderIndex, heShortDesc, heDesc
)
VALUES (?, ?, ?, ?, ?, ?, ?);

setDescriptions:
UPDATE category
SET heShortDesc = :heShortDesc,
    heDesc = :heDesc
WHERE id = :id;
```

אין להשתמש ב־`COALESCE` או ב־`CASE` בשאילתת העדכון. סמנטיקת השארה/החלפה/מחיקה נקבעת ב־Kotlin לפני הקריאה לשאילתה.

#### ד. המרת מודל DB למודל ליבה

ב־`dao/src/commonMain/kotlin/io/github/kdroidfilter/seforimlibrary/dao/extensions/ModelExtensions.kt`, עדכן את `Category.toModel()` כך שיעביר גם:

```kotlin
heShortDesc = heShortDesc,
heDesc = heDesc,
```

אל תשנה במסגרת משימה זו התנהגות אחרת של ההמרה.

#### ה. טיפוסי העזר של ה־repository

הוסף ליד `SeforimRepository` שני טיפוסים בעלי שמות מפורשים:

```kotlin
data class CategoryDescriptionUpdate(
    val categoryId: Long,
    val heShortDesc: String?,
    val heDesc: String?,
)

data class CategoryDescriptionRow(
    val categoryId: Long,
    val canonicalPath: String,
    val heShortDesc: String?,
    val heDesc: String?,
)
```

הוסף KDoc לשני הטיפוסים ולכל פונקציית repository ציבורית חדשה, בהתאם לכללי ה־repository.

#### ו. מסלולי ה־insert

ב־`SeforimRepository`:

1. עדכן את `insertCategory(category: Category)` כך שיעביר ל־`insert` גם `category.heShortDesc` ו־`category.heDesc`.
2. הוסף ל־`insertCategoryWithId` את הפרמטרים הבאים בסוף החתימה, עם defaults כדי לא לשבור call-sites אחרים:

   ```kotlin
   heShortDesc: String? = null,
   heDesc: String? = null,
   ```

3. העבר אותם ל־`insertWithId`.

#### ז. קריאת כל הקטגוריות לפי נתיב מלא

הוסף:

```kotlin
suspend fun getAllCategoryDescriptionRows(): List<CategoryDescriptionRow>
```

המימוש חייב להשתמש בשאילתה יחידה וב־memoization, ללא שאילתה נפרדת לכל קטגוריה. עלות בניית הנתיבים היא `O(Σ עומקי הנתיבים)`:

1. קרא פעם אחת את `categoryQueriesQueries.selectAll`.
2. בנה `Map<Long, DbCategory>` לפי `id`. מכיוון שכבר מיובא במאגר גם מודל הליבה `Category`, השתמש ב־`import io.github.kdroidfilter.seforimlibrary.db.Category as DbCategory` או הסתמך על type inference; אל תיצור מחלקה חדשה בשם הזה.
3. בנה את הנתיב של כל קטגוריה באמצעות DFS עם memoization על `parentId`.
4. דרוש שכל מקטע לאחר נרמול אינו ריק ואינו מכיל `/`.
5. בנה את הנתיב בעזרת `normalizeCategoryPath`, כדי שהנתיב יתאים לחוזה של קובץ ה־CSV.
6. החזר רשימה של `CategoryDescriptionRow`.

יש לעצור עם הודעה הכוללת את ה־id והכותרת כאשר נמצא אחד מאלה:

- `parentId` שאינו קיים;
- מעגל בשרשרת ההורים;
- שני ids שמייצרים אותו `canonicalPath`.

#### ח. עדכון אצווה טרנזקציוני

הוסף ל־repository פונקציה אחת לעדכון התיאורים:

```kotlin
suspend fun setCategoryDescriptionsBatch(
    updates: List<CategoryDescriptionUpdate>,
)
```

המימוש:

1. אם `updates` ריקה — חזור מיד.
2. דרוש שאין שני updates עם אותו `categoryId`.
3. עבור פעם אחת ל־`Dispatchers.IO`.
4. בתוך `database.transaction`, קרא `setDescriptions` לכל איבר.
5. רק לאחר שהטרנזקציה הסתיימה בהצלחה, הסר מ־`categoryCache` את כל ה־ids שהשתנו.
6. אל תחשוף את אובייקט ה־database לקוד ה־post-process ואל תבצע `withContext` נפרד לכל שורה.

כך כל קובץ ה־CSV מוחל אטומית, עם מעבר coroutine יחיד ומינימום invalidation של המטמון.

### 1.3 קריאת תיאורי הקטגוריות מ־`table_of_contents.json`

אין להוסיף את קריאת התיאורים לתוך התנאי הקיים `order != null && categoryPath.isNotEmpty()`. התנאי הזה משמיט קטגוריות שורש וקטגוריות ללא `order`.

#### א. טיפוס התוצאה

החלף את ה־`Pair` שמוחזר מ־`parseTableOfContentsOrders` ב־data class ברור, ושנה את שם הפונקציה כך שישקף את תפקידה המלא:

```kotlin
internal data class CategoryDescriptions(
    val heShortDesc: String?,
    val heDesc: String?,
)

internal data class ParsedTableOfContents(
    val categoryOrders: Map<String, Int>,
    val bookOrders: Map<String, Int>,
    val categoryDescriptions: Map<String, CategoryDescriptions>,
)

internal fun parseTableOfContentsMetadata(
    dbRoot: Path,
    json: Json,
    logger: Logger,
): ParsedTableOfContents
```

הקובץ נקרא ומפוענח פעם אחת בלבד.

#### ב. walker יחיד לכל עץ ה־TOC

צור פונקציה רקורסיבית שמקבלת `item` ואת נתיב ההורים בעברית. העבר דרכה גם את הצמתים העליונים; אין להשאיר לולאה נפרדת שמטפלת בשורשים רק כאשר יש להם `order`.

בכל צומת:

1. קרא את `heCategory` ואת `category`.
2. אם קיימים `heShortDesc` או `heDesc` לא־ריקים, דרוש `heCategory` לא־ריק. תיאור עברי ללא שם קטגוריה עברי הוא נתון לא תקין ויש להיכשל עם שם הצומת.
3. בנה את הנתיב לתיאורים כך:

   ```kotlin
   val rawPath = parentHebrewPath + normalizeCategoryPathSegment(heCategory)
   val canonicalPath = flattenTalmudCategories(rawPath).joinToString("/")
   ```

4. נרמל כל תיאור באמצעות `trim().takeIf { it.isNotEmpty() }`.
5. הכנס למפת `categoryDescriptions` רק כאשר לפחות אחד משני התיאורים אינו `null`.
6. חשב את `order` בנפרד. היעדר `order` אינו מונע שמירת תיאור ואינו מונע ירידה ל־`contents`.
7. קרא גם את סדרי הספרים כפי שהקוד הקיים עושה היום, כדי לא לשנות את התנהגות המיון.
8. המשך רקורסיבית לכל `contents`, גם אם לצומת הנוכחי אין `order`.

לצורך נתיב הילדים, השתמש ב־`heCategory` כאשר הוא קיים. בצומת ללא `heCategory` וללא תיאור מותר לשמר את התנהגות המיון הקיימת באמצעות `category`; אין ליצור בעזרתו מפתח במפת התיאורים העבריים.

#### ג. התנגשויות ושגיאות קלט

בעת הכנסת תיאור למפה:

- מפתח חדש — הכנס אותו;
- אותו מפתח ואותם שני ערכים — השאר רשומה אחת;
- אותו מפתח עם ערך שונה באחד השדות — עצור והצג את המפתח ואת שני זוגות הערכים.

`table_of_contents.json` חסר, JSON שאינו תקין או מבנה שאינו מערך הם שגיאת build. הסר את ההתנהגות שמחזירה מפות ריקות ואת ה־`catch` שבולע חריגות. מותר להוסיף הקשר לחריגה, אך חובה לזרוק אותה מחדש.

#### ד. חיבור לייבוא הישיר

ב־`SefariaDirectImporter.import()`:

```kotlin
val toc = parseTableOfContentsMetadata(dbRoot, json, logger)
val categoryOrders = toc.categoryOrders
val bookOrders = toc.bookOrders
val categoryDescriptions = toc.categoryDescriptions
```

בתוך `ensureCategoryPath`, לאחר חישוב `key`:

```kotlin
val descriptions = categoryDescriptions[key]
```

העבר את הערכים אל `bindings.upsertCategory`.

שמור בקבוצה מקומית את מפתחות התיאור שנמצאו בזמן יצירת קטגוריה. בסיום הייבוא רשום רק שלושה מונים: מספר התיאורים שפוענחו מה־TOC, מספר המפתחות ששימשו בקטגוריה שנוצרה ומספר המפתחות שלא שימשו. המונים מיועדים לאבחון build ואינם תנאי hard-coded; אין להדפיס את טקסט התיאורים.

ב־`generator/common/src/jvmMain/kotlin/io/github/kdroidfilter/seforimlibrary/common/ids/IdAllocatorBindings.kt`, הוסף ל־`upsertCategory`:

```kotlin
heShortDesc: String? = null,
heDesc: String? = null,
```

והעבר אותם ל־`repository.insertCategoryWithId`.

ה־defaults נחוצים משום שה־binding משמש גם מקומות שאינם יבוא ספריא.

### 1.4 כללי התלמוד

ה־TOC מכיל שלושה צמתים שונים:

- `תלמוד` — צומת שורש עם תיאור קצר וארוך; הצומת עצמו אינו נוצר ב־DB בגלל `flattenTalmudCategories`.
- `תלמוד/בבלי` — צומת בעל `heDesc` משלו, שנשמר בקטגוריה `תלמוד בבלי`.
- `תלמוד/ירושלמי` — צומת בעל `heDesc` משלו, שנשמר בקטגוריה `תלמוד ירושלמי`.

לכן:

1. ה־walker שומר את שלושת הצמתים במפת המקור.
2. בזמן ה־insert, התיאור הארוך של בבלי ושל ירושלמי נזרע מהצומת המתאים להם.
3. מפתח `תלמוד` לא ימצא קטגוריה להכנסה, וזה צפוי; אין ליצור עבורו קטגוריה מלאכותית.
4. בקובץ ה־CSV הוסף שורה ל־`תלמוד בבלי` ושורה ל־`תלמוד ירושלמי`, ובהן `heShortDescNew` הוא התיאור הקצר של צומת האב `תלמוד`.
5. השאר `heDescNew` ריק בשתי השורות, כדי לשמור את התיאור הארוך הייחודי שנזרע מכל צומת בן.

### 1.5 שמירת תיאורים בזמן rename או merge

ה־post-process `RenameCategoriesPostProcess` מבצע גם שינוי שם פשוט וגם מיזוג שמוחק את שורת המקור. יש לעדכן את `renameOrMergeCategory` כך שתיאורים לא יימחקו בזמן מיזוג.

בשינוי שם פשוט אין פעולה נוספת: אותה שורה ואותו `id` נשמרים, ולכן גם התיאורים נשמרים.

במיזוג, לפני העברת הספרים ומחיקת המקור, קרא את שני שדות התיאור של המקור ושל היעד. עבור כל שדה בנפרד השתמש בכלל הבא:

| מקור | יעד | ערך שנשמר ביעד |
|---|---|---|
| `NULL` | כל ערך | ערך היעד |
| ערך | `NULL` | ערך המקור |
| אותו ערך | אותו ערך | אותו ערך |
| ערכים שונים | ערכים שונים | שגיאה מפורשת; אין למחוק את המקור |

רק לאחר פתרון שני השדות ועדכון היעד מותר להעביר ספרים ותתי־קטגוריות ולמחוק את המקור. כל פעולת `renameCategories` כבר מתבצעת בטרנזקציה; חריגה חייבת לבטל את כולה.

### 1.6 קובץ העריכה ב־`ForDB`

צור ב־repository `otzaria-library` את הקובץ:

`ForDB/sefaria_category_changes.csv`

הכותרת המדויקת, כולל הסדר:

```csv
"categoryPath","heShortDesc","heDesc","heShortDescNew","heDescNew"
```

משמעות העמודות:

- `categoryPath` — מפתח ההתאמה. נתיב סופי לאחר renames, מופרד ב־`/`, וכל `"` מנורמל ל־`״`.
- `heShortDesc`, `heDesc` — צילום של ערכי המקור לצורכי עבודת האוצר בלבד. הם אינם נכתבים ל־DB.
- `heShortDescNew`, `heDescNew` — ההחלטה האוצרית שנכתבת ל־DB.

שמור את הקובץ ב־UTF-8, עם `QUOTE_ALL`, ואל תחליף שורות פנימיות בתיאור ברצף escape ידני. ה־CSV parser תומך בשדה מצוטט המשתרע על כמה שורות פיזיות.

סמנטיקת כל תא `New`:

- תא ריק — השאר את הערך הקיים ב־DB ללא שינוי;
- `[מחק]` לאחר `trim()` — כתוב `NULL`;
- כל טקסט אחר — כתוב `trim()` של הטקסט, תוך שמירת שורות פנימיות בשדה מרובה־שורות.

הקובץ חייב לכלול לפני מיזוג קוד ה־consumer את שתי שורות התלמוד המתוארות בסעיף 1.4. בכל אחת מהן `heShortDesc` ריק, `heDesc` מכיל את התיאור הארוך של צומת הבן, `heShortDescNew` מכיל את התיאור הקצר של צומת האב `תלמוד`, ו־`heDescNew` ריק. קובץ עם header בלבד מתאים רק להכנת התשתית ואינו עומד בתנאי הקבלה של התיקון.

### 1.7 החלת קובץ ה־CSV

הרחב את `SeedAllMetadataPostProcess.kt`; אל תיצור משימת Gradle או תהליך JVM נוסף. המשימה הקיימת כבר:

- רצה אחרי `renameCategories` ואחרי `appendOtzaria`;
- פותחת את ה־repository;
- מורידה את ארכיון `ForDB` פעם אחת ושומרת אותו במטמון התהליך.

ב־`FOR_DB_CSV_FILES` הוסף:

```kotlin
"categoryDescriptions" to "sefaria_category_changes.csv"
```

כך בדיקת הקבצים החסרים הקיימת תפיק שגיאה ברורה לפני `getValue`.

#### א. parser

אל תייצג את שלוש הפעולות האפשריות באמצעות `String?`, משום שאז `null` עלול להתפרש גם כ“השאר” וגם כ“מחק”. הוסף טיפוסים מפורשים:

```kotlin
sealed interface DescriptionEdit {
    data object Keep : DescriptionEdit
    data object Clear : DescriptionEdit
    data class Replace(val value: String) : DescriptionEdit
}

data class CategoryDescriptionOverride(
    val csvRecordNumber: Int,
    val canonicalPath: String,
    val shortEdit: DescriptionEdit,
    val longEdit: DescriptionEdit,
)
```

הוסף helper יחיד `parseDescriptionEdit(raw: String): DescriptionEdit`:

- `raw.trim().isEmpty()` מחזיר `Keep`;
- `raw.trim() == "[מחק]"` מחזיר `Clear`;
- אחרת מוחזר `Replace(raw.trim())`.

הוסף `parseCategoryDescriptionOverrides` המשתמש ב־`parseForDbCsvRecords`, כדי לתמוך בשדות מצוטטים ובשורות פנימיות. ה־parser חייב:

1. לדרוש התאמה מלאה של חמשת שמות ה־header ובאותו סדר;
2. לדרוש `categoryPath` לא־ריק;
3. לדחות `/` בתחילת הנתיב, `/` בסופו או `//`; אף מקטע אינו רשאי להיות ריק;
4. לדרוש שהנתיב כבר מנורמל: פיצול ב־`/` והפעלה של `normalizeCategoryPath` חייבים להחזיר בדיוק את הקלט;
5. להיכשל על `categoryPath` כפול, עם מספרי שתי הרשומות. אין לכנות אותם מספרי שורות פיזיות, משום ששדה מצוטט עשוי להשתרע על כמה שורות;
6. להחזיר רשימה, לא `Map` שנבנית באמצעות `toMap()` ועלולה להסתיר כפילויות.

עמודות המקור `heShortDesc` ו־`heDesc` נקראות כדי לאמת שלרשומה יש בדיוק חמישה שדות, אך אינן נכנסות ל־`CategoryDescriptionOverride` ואינן משפיעות על הכתיבה.

#### ב. חישוב העדכונים

הוסף `applyCategoryDescriptionOverrides`:

1. קרא פעם אחת `repository.getAllCategoryDescriptionRows()` ובנה מפה לפי `canonicalPath`.
2. עבור כל רשומת CSV, דרוש התאמה אחת לקטגוריה. נתיב חסר הוא שגיאת build.
3. חשב את שני הערכים הסופיים באמצעות helper טהור:

   ```kotlin
   fun applyDescriptionEdit(current: String?, edit: DescriptionEdit): String? =
       when (edit) {
           DescriptionEdit.Keep -> current
           DescriptionEdit.Clear -> null
           is DescriptionEdit.Replace -> edit.value
       }
   ```

   הפעל אותו בנפרד על התיאור הקצר ועל התיאור הארוך.
4. אם שני הערכים הסופיים שווים לערכים הקיימים — אל תיצור update.
5. אסוף את השינויים בלבד ל־`List<CategoryDescriptionUpdate>`.
6. קרא פעם אחת `repository.setCategoryDescriptionsBatch(updates)`.
7. רשום ללוג: מספר רשומות CSV, מספר עדכונים שבוצעו ומספר רשומות שלא דרשו שינוי. אל תדפיס את טקסט התיאורים המלא ללוג.

אין לכתוב כל שורה מיד בזמן הפענוח: קודם מפענחים ומאמתים את הקובץ כולו, ורק לאחר שכל הבדיקות עברו מבצעים טרנזקציה אחת.

### 1.8 בדיקות של חלק 1

#### בדיקות parser של ה־TOC

הוסף בדיקות תחת `generator/sefariasqlite/src/jvmTest` המכסות לפחות:

1. קטגוריית שורש עם קצר וארוך נשמרת.
2. קטגוריה ללא `order` נשמרת, וגם הילדים שלה נסרקים.
3. קטגוריה עם `order` נשמרת גם במפת הסדר וגם במפת התיאורים.
4. `תלמוד/בבלי` ו־`תלמוד/ירושלמי` משתטחים למפתחות הסופיים ושומרים את התיאור הארוך של כל בן.
5. `normalizeCategoryPathSegment` ממיר `"` ל־`״` בכל מקטע, ו־`sanitizeFolder` משתמש בו.
6. תיאור המורכב מרווחים בלבד הופך ל־`null`.
7. שני צמתים שמייצרים אותו נתיב ואותם ערכים מתקבלים כרשומה אחת.
8. שני צמתים שמייצרים אותו נתיב וערכים שונים גורמים לכשל.
9. קובץ חסר, JSON פגום ומבנה עליון שאינו מערך גורמים לכשל.

#### בדיקות repository ו־merge

הוסף בדיקות תחת `dao/src/jvmTest` ובדיקות post-process תחת `generator/sefariasqlite/src/jvmTest`:

1. round-trip של `insertCategory` עם שני תיאורים.
2. round-trip של `insertCategoryWithId` עם שני תיאורים.
3. `getAllCategoryDescriptionRows` בונה נתיבים נכונים בעץ בן שלוש רמות.
4. parent חסר, מעגל ונתיב כפול גורמים לכשל.
5. batch של כמה קטגוריות מתעדכן במלואו.
6. כשל באמצע batch מבטל את כל העדכונים. בטסט צור trigger זמני מסוג `BEFORE UPDATE` שמבצע `RAISE(ABORT, 'test')` עבור ה־id השני, וכך ודא שגם העדכון הראשון בוטל.
7. merge מעתיק ערך כאשר רק למקור יש ערך.
8. merge שומר ערך כאשר רק ליעד יש ערך.
9. merge מקבל ערכים זהים.
10. merge עם ערכים שונים נכשל ואינו מוחק את המקור.

#### בדיקות CSV

כסה לפחות:

1. דריסה של קצר ושל ארוך.
2. `[מחק]` הופך ל־`NULL`.
3. תא `New` ריק שומר את הערך שנזרע.
4. שדה מרובה־שורות נשמר נכון.
5. header שגוי, נתיב ריק, נתיב לא מנורמל, נתיב כפול ונתיב שאינו קיים גורמים לכשל לפני כתיבה.
6. רשומה שאינה משנה ערך אינה נשלחת ל־batch.
7. שתי שורות התלמוד מוסיפות קצר ושומרות את הארוך שנזרע.

#### בדיקת build מלא

הרץ:

```bash
./gradlew :core:jvmTest :dao:jvmTest :sefariasqlite:jvmTest
./gradlew generateSeforimDb
```

בייצוא המקומי מ־2026-07-09 קיימים 152 צומתי קטגוריה עם תיאור עברי:

- 124 מתאימים ישירות לקטגוריה סופית ב־DB: 118 עם קצר ו־6 עם ארוך בלבד;
- 27 אינם מתאימים לקטגוריה שנוצרה;
- צומת השורש `תלמוד` נעלם בתהליך ה־flatten, והתיאור הקצר שלו מוחל על שתי קטגוריות התלמוד דרך ה־CSV.

המספרים משמשים לבדיקת הקבלה של הייצוא הנוכחי, לא כתנאי hard-coded בקוד הייצור.

בדוק לפחות:

```sql
SELECT title, heShortDesc, heDesc
FROM category
WHERE title IN ('תנ״ך', 'תלמוד בבלי', 'תלמוד ירושלמי');

SELECT COUNT(*) AS describedCategories
FROM category
WHERE NULLIF(TRIM(heShortDesc), '') IS NOT NULL
   OR NULLIF(TRIM(heDesc), '') IS NOT NULL;

SELECT COUNT(*) AS invalidStoredValues
FROM category
WHERE heShortDesc = '' OR heDesc = ''
   OR heShortDesc = '[מחק]' OR heDesc = '[מחק]';
```

תנאי הקבלה:

- `תנ״ך` מכילה קצר וארוך שנזרעו מה־TOC;
- שתי קטגוריות התלמוד מכילות קצר מה־CSV וארוך מהצומת הייעודי ב־TOC;
- `describedCategories` שווה 124 עבור הייצוא וקובץ ה־ForDB שנמדדו לעיל;
- הנתיב `תנ״ך/תרגומים/תרגום אונקלוס`, שהוא צומת מתואר ללא `order`, מכיל תיאור לפי `getAllCategoryDescriptionRows`;
- `invalidStoredValues` שווה 0; היעדר ערך מיוצג ב־`NULL`.

#### בדיקת דלתא בסכימה 2

לאחר שקיים baseline בסכימה 2, שנה רק ערך אחד ב־`sefaria_category_changes.csv`, בנה DB נוסף והפק patch.

ודא:

- `upsert_category` מכיל רק את הקטגוריה ששונתה;
- `upsert_schema_meta` מותר וצפוי עקב שינוי `db_version`;
- אין שינוי בטבלת תוכן אחרת;
- ה־id של הקטגוריה נשאר קבוע;
- לאחר החלת ה־patch, `toContentHash` זהה ל־DB החדש.

### 1.9 סדר מימוש מחייב לחלק 1

1. ב־`otzaria-library`, צור את קובץ ה־CSV עם ה־header ושתי שורות התלמוד, ודא שה־workflow עדכן את asset ‏`fordb_latest.zip`.
2. ב־`SeforimLibrary`, הוסף את utility הנרמול המשותף ואת עמודות הסכימה.
3. עדכן מודלים, שאילתות ו־repository, והריץ את בדיקות `core`/`dao` הרלוונטיות.
4. שכתב את parser ה־TOC וחבר את התיאורים ל־`ensureCategoryPath` ול־`IdAllocatorBindings`.
5. הוסף שמירת תיאורים במיזוג קטגוריות ואת בדיקות ה־merge.
6. הוסף את parser וה־apply של קובץ ה־CSV בתוך `SeedAllMetadataPostProcess`.
7. הרץ את כל בדיקות Part 1; תקן כל כשל לפני מעבר לשלב הבא.
8. הרץ build מלא ובצע את שאילתות הקבלה.
9. רק לאחר שקיים baseline בסכימה 2, הרץ את בדיקת הדלתא המתוארת לעיל.

## חלק 2 — הצגה באפליקציה (`otzaria`)

### 2.1 הרחבת מודל ה־DB באפליקציה

בקובץ `lib/migration/models/category.dart`, הוסף:

```dart
final String? heShortDesc;
final String? heDesc;
```

עדכן את כל חלקי המודל:

- constructor;
- `copyWith`;
- `fromJson`;
- `toJson`;
- `toString`;
- `operator ==`;
- `hashCode`.

ב־`fromJson` השתמש במפורש ב־casts nullable:

```dart
heShortDesc: json['heShortDesc'] as String?,
heDesc: json['heDesc'] as String?,
```

`CategoryDao.getAllCategoryRows` כבר מריץ `SELECT *`, ולכן אין לשנות את שאילתת הקריאה. ב־DB מסכימה 1 המפתחות אינם קיימים במפה ויתקבל `null`; אין לבצע שאילתה נוספת ואין לקרוא קובץ חיצוני.

### 2.2 חיבור הקטלוג הראשי לשדות ה־DB

ב־`lib/data/data_providers/database_library_provider.dart`, אתר את `_buildCatalogCategoryRecursiveOptimized` והחלף רק את מקור שני שדות התיאור:

```dart
final category = Category(
  title: dbCategory.title,
  description: dbCategory.heDesc ?? '',
  shortDescription: dbCategory.heShortDesc ?? '',
  order: dbCategory.orderIndex,
  subCategories: [],
  books: [],
  parent: parent,
);
```

כללים מחייבים:

- אין להשתמש ב־`metadata[dbCategory.title]` עבור קטגוריות הקטלוג הראשי.
- אין להסיר את הפרמטר `metadata` מהפונקציה במסגרת המשימה: הוא עדיין משמש מסלולי ספרים אחרים.
- אין לשנות את `file_system_library_provider.dart`.
- אין לשנות את `_buildUserBooksCatalogCategoryRecursive` או את DB הספרים האישיים.
- אין להוסיף קריאת DB נוספת לכל קטגוריה; כל הקטגוריות כבר נטענות ב־query יחיד לפני בניית העץ.

### 2.3 רכיב מידע משותף לדיאלוגים

הפונקציה `_buildInfoSection` ב־`lib/text_book/view/book_source_dialog.dart` פרטית לקובץ ולכן אינה זמינה לדיאלוג הקטגוריה. כדי לא לשכפל את אותו UI, חלץ אותה ל־widget משותף:

`lib/widgets/dialogs/details_info_section.dart`

```dart
/// מקטע תווית־וערך אחיד לדיאלוגי מידע.
class DetailsInfoSection extends StatelessWidget {
  const DetailsInfoSection({
    super.key,
    required this.title,
    required this.value,
    this.valueDirection,
  });

  final String title;
  final String value;
  final TextDirection? valueDirection;
}
```

ה־`build` שלו יכיל את אותו layout וסגנון שיש כיום ב־`_buildInfoSection`. לאחר מכן:

1. יצא אותו דרך `lib/widgets/dialogs/dialogs_exports.dart`.
2. החלף את הקריאות ב־`book_source_dialog.dart` ל־`DetailsInfoSection`.
3. מחק את `_buildInfoSection` הפרטי רק לאחר שכל הקריאות הוחלפו.
4. אל תשנה את העיצוב או התוכן של דיאלוג הספר במסגרת החילוץ.

### 2.4 דיאלוג הקטגוריה

צור:

`lib/library/view/category_details_dialog.dart`

והוסף פונקציה ציבורית:

```dart
/// מציג את כל פרטי הקטגוריה הזמינים.
Future<void> showCategoryDetailsDialog(
  BuildContext context,
  Category category,
) async {
  await showSingleActionDialog(
    context: context,
    title: 'אודות הקטגוריה',
    confirmText: 'סגור',
    customContent: _CategoryDetailsDialogContent(category: category),
  );
}
```

`_CategoryDetailsDialogContent` הוא widget פרטי באותו קובץ, המציג את שם הקטגוריה ואת התיאורים הקיימים.

בתוכן הדיאלוג:

1. הצג תמיד `DetailsInfoSection(title: 'שם הקטגוריה:', value: category.title)`.
2. הצג `תיאור קצר:` רק אם `category.shortDescription.trim().isNotEmpty`.
3. הצג `תיאור מורחב:` רק אם `category.description.trim().isNotEmpty`.
4. עטוף את התוכן ב־`SingleChildScrollView`, כדי שתיאור ארוך לא יחרוג מגובה החלון.
5. השתמש ב־`SelectableText` שמספק `DetailsInfoSection`; אל תקצר את הטקסט בתוך הדיאלוג.

### 2.5 כרטיס הקטגוריה

ב־`lib/library/view/grid_items.dart`:

1. השאר את הצגת `category.shortDescription` הקיימת בכרטיס, עם שתי שורות לכל היותר.
2. הוסף פונקציית עזר טהורה וניתנת לבדיקה:

   ```dart
   /// הטקסט שיוצג ב־Tooltip של לחצן המידע לקטגוריה.
   String? categoryInfoText(Category category) {
     final full = category.description.trim();
     if (full.isNotEmpty) return full;

     final short = category.shortDescription.trim();
     if (short.isNotEmpty) return short;

     return null;
   }
   ```

   כלל התצוגה קבוע: הטקסט המורחב מוצג כאשר הוא קיים; אחרת מוצג הקצר. אין פנייה למקור נתונים אחר.

3. חשב פעם אחת בתחילת `build`:

   ```dart
   final infoText = categoryInfoText(category);
   ```

4. הצג את אייקון המידע רק כאשר `infoText != null`. כך קטגוריה עם ארוך בלבד עדיין מקבלת לחצן מידע.
5. השתמש ב־`Tooltip` עם `message: infoText` ועם אותו padding, רוחב, style ו־decoration של Tooltip הספר.
6. החלף את ה־`Icon` הסטטי ב־`IconButton` הקורא:

   ```dart
   onPressed: () => showCategoryDetailsDialog(context, category)
   ```

7. השתמש ב־`FluentIcons.info_24_regular`; אין להוסיף icon package או דיאלוג מותאם אחר.

### 2.6 בדיקות של חלק 2

#### בדיקות מודל וקריאת DB

צור `test/migration/category_model_test.dart`, והרחב את `test/data_providers/database_library_provider_test.dart`. כסה:

1. `Category.fromJson` עם שתי העמודות מחזיר את שני הערכים.
2. `Category.fromJson` ללא שני המפתחות מחזיר `null` ואינו זורק.
3. `toJson`, שוויון ו־`hashCode` כוללים את שני השדות.
4. בניית קטגוריה ב־`_buildCatalogCategoryRecursiveOptimized` מעבירה את ערכי ה־DB למודל התצוגה.
5. כאשר ערכי ה־DB הם `NULL`, מודל התצוגה מקבל מחרוזות ריקות.

#### בדיקות widget

הרחב את `test/library/view/grid_items_test.dart` וכסה ארבעה מצבים:

| נתונים | טקסט בכרטיס | Tooltip | לחצן מידע | תוכן הדיאלוג |
|---|---|---|---|---|
| קצר + ארוך | קצר | ארוך | קיים | שם, קצר וארוך |
| קצר בלבד | קצר | קצר | קיים | שם וקצר |
| ארוך בלבד | לא מוצג תיאור קצר | ארוך | קיים | שם וארוך |
| שניהם ריקים | לא מוצג | לא קיים | לא קיים | לא ניתן לפתיחה |

בכל בדיקת דיאלוג לחץ בפועל על ה־`IconButton`, ודא שהכותרת `אודות הקטגוריה` מופיעה, וסגור באמצעות הכפתור `סגור` כדי שלא יישאר route פתוח בסוף הבדיקה.

מכיוון ש־`DetailsInfoSection` מחליף helper קיים בדיאלוג הספר, הרץ גם את `test/text_book/view/book_source_dialog_test.dart` ללא שינוי בציפיות הקיימות. זהו מבחן הרגרסיה לכך שהחילוץ לא שינה את דיאלוג הספר.

#### אימות סופי באפליקציה

לאחר כל שינוי קוד ב־Dart, הרץ `dart format` על הקבצים ששונו ולאחריו `flutter analyze`; אל תמשיך לשינוי הבא לפני שכל שגיאות ה־analyze תוקנו. בסיום הרץ:

```bash
dart format \
  lib/migration/models/category.dart \
  lib/data/data_providers/database_library_provider.dart \
  lib/widgets/dialogs/details_info_section.dart \
  lib/widgets/dialogs/dialogs_exports.dart \
  lib/text_book/view/book_source_dialog.dart \
  lib/library/view/category_details_dialog.dart \
  lib/library/view/grid_items.dart \
  test/migration/category_model_test.dart \
  test/data_providers/database_library_provider_test.dart \
  test/library/view/grid_items_test.dart
flutter analyze
flutter test test/migration/category_model_test.dart
flutter test test/library/view/grid_items_test.dart
flutter test test/data_providers/database_library_provider_test.dart
flutter test test/text_book/view/book_source_dialog_test.dart
```

לאחר שהבדיקות עוברות, פתח ידנית את `תנ״ך`, `תלמוד בבלי` ו־`תלמוד ירושלמי` מול DB מסכימה 2 ובדוק:

- הקצר מוצג בכרטיס כאשר הוא קיים;
- ריחוף מציג את הטקסט שנקבע לפי כלל התצוגה;
- לחיצה פותחת דיאלוג עם כל התיאורים הקיימים;
- קטגוריה ללא תיאורים אינה מציגה לחצן מידע;
- הפעלת האפליקציה מול DB מסכימה 1 אינה קורסת ומציגה קטגוריות ללא תיאור.

התיקון הושלם רק כאשר בדיקות חלק 1, build מלא, בדיקת הדלתא, `flutter analyze` ובדיקות חלק 2 עוברים ללא שגיאות.
