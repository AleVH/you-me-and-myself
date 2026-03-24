// File: src/main/kotlin/com/youmeandmyself/summary/model/CodeElement.kt
package com.youmeandmyself.summary.model

/**
 * Represents a detected code element (class, method, function, etc.).
 *
 * Produced by [CodeStructureProvider.detectElements]. Consumed by
 * [SummaryPipeline] for hierarchical summarization and by [SummaryCache]
 * for element-level caching.
 *
 * All fields are populated by PSI — no defaults, no guessing.
 *
 * ## Cache Key
 *
 * [signature] is the stable, unique-within-file identifier used as the cache key.
 * It is built from the element's fully qualified name and parameter types (for methods),
 * which means overloads are distinguished correctly.
 *
 * ## Semantic Hashing
 *
 * [offsetRange] identifies where this element lives in the file. It is used by
 * [CodeStructureProvider.computeElementHash] to build a semantic fingerprint
 * that only changes when the element's behavior changes — not on cosmetic edits
 * like renaming local variables, reformatting, or adding comments.
 *
 * @property name Simple name of the element (e.g., "doThing", "UserService").
 * @property body Full source text of the element, including its declaration and body.
 * @property kind What kind of element this is (class, method, interface, etc.).
 * @property offsetRange Character offset range within the containing file. Provided by
 *           PSI via element.textRange. Used for element-level hashing.
 * @property parentName Simple name of the containing class, or null for top-level
 *           elements. E.g., for method "doThing" inside class "UserService", this
 *           is "UserService".
 * @property signature Stable, unique-within-file identifier used as cache key.
 *           Format examples:
 *           - Class: "com.foo.UserService"
 *           - Method: "com.foo.UserService#doThing(String,Int)"
 *           - Top-level function: "#doThing(String,Int)"
 */
data class CodeElement(
    val name: String,
    val body: String,
    val kind: CodeElementKind,
    val offsetRange: IntRange,
    val parentName: String? = null,
    val signature: String
)
