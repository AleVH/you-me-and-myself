// File: src/main/kotlin/com/youmeandmyself/summary/structure/ElementLevel.kt
package com.youmeandmyself.summary.structure

/**
 * Determines what kind of structural context to extract.
 *
 * Passed to [CodeStructureProvider.extractStructuralContext] to select
 * the appropriate extraction logic. Each level extracts different
 * structural information:
 *
 * - [METHOD]: annotations, visibility, parameter types, return type, receiver, suspend
 * - [CLASS]: annotations, visibility, kind, type parameters, supers, member counts
 * - [FILE]: package, import summary, top-level declaration counts
 */
enum class ElementLevel {
    METHOD,
    CLASS,
    FILE
}
