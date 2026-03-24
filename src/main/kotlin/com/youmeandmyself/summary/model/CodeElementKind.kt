// File: src/main/kotlin/com/youmeandmyself/summary/model/CodeElementKind.kt
package com.youmeandmyself.summary.model

/**
 * Classifies the kind of code element detected by [CodeStructureProvider].
 *
 * Used to filter detection results (e.g., "give me only classes")
 * and to select the appropriate summarization template.
 *
 * Values are determined from the IDE's PsiElement.node.elementType —
 * the IDE already classified each element, we just read its label.
 */
enum class CodeElementKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    METHOD,
    FUNCTION,
    PROPERTY,
    CONSTRUCTOR,
    /** Fallback for element types not explicitly mapped. */
    OTHER
}
