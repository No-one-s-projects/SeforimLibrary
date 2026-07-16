package io.github.kdroidfilter.seforimlibrary.core.text

/** Normalizes one category-path segment for stable DB and CSV matching. */
fun normalizeCategoryPathSegment(value: String): String =
    value.replace("\"", "״").trim()

/** Joins normalized category segments using the canonical slash separator. */
fun normalizeCategoryPath(segments: List<String>): String =
    segments.map(::normalizeCategoryPathSegment).joinToString("/")
