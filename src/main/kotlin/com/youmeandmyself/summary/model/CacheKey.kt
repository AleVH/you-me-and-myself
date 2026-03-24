// File: src/main/kotlin/com/youmeandmyself/summary/model/CacheKey.kt
package com.youmeandmyself.summary.model

/**
 * Composite key for the summary cache.
 *
 * Supports file-level and element-level (method, class) cache entries
 * within the same cache structure.
 *
 * ## Key Format
 *
 * The [toMapKey] method produces the string used as the ConcurrentHashMap key:
 * - File-level (elementSignature is null): just the file path → "/src/UserService.kt"
 * - Element-level: path + separator + signature → "/src/UserService.kt::com.foo.UserService#doThing(String,Int)"
 *
 * File-level entries use the path alone for backward compatibility with
 * existing cache data.
 *
 * @property path Absolute file path.
 * @property elementSignature Identifies a sub-file element (class or method).
 *           Null for file-level entries.
 */
data class CacheKey(
    val path: String,
    val elementSignature: String? = null
) {
    /**
     * String representation used as the map key in ConcurrentHashMap.
     *
     * File-level entries use the path alone for backward compatibility.
     * Element-level entries use "::" as separator — this is safe because
     * "::" does not appear in file paths or Java/Kotlin FQNs.
     */
    fun toMapKey(): String = if (elementSignature != null) "$path::$elementSignature" else path
}
