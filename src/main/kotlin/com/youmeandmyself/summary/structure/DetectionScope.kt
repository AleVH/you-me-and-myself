// File: src/main/kotlin/com/youmeandmyself/summary/structure/DetectionScope.kt
package com.youmeandmyself.summary.structure

/**
 * Controls what [CodeStructureProvider.detectElements] returns.
 *
 * Used to implement demand-driven scoping: only detect what the current
 * user question requires, rather than the entire file.
 *
 * ## Example Usage
 *
 * - User asks about a specific method → [SingleMethod]
 * - User asks about a class → [SingleClass] with includeMethods = true
 * - File-level summary needed → [All]
 * - Watcher checking element hashes → [All]
 */
sealed class DetectionScope {

    /** Detect all elements in the file (classes and their methods). */
    object All : DetectionScope()

    /** Detect only top-level classes/interfaces/objects/enums. No methods. */
    object ClassesOnly : DetectionScope()

    /**
     * Detect all methods within a specific class.
     *
     * @property className Simple name of the class (not FQN).
     */
    data class MethodsOf(val className: String) : DetectionScope()

    /**
     * Detect a single method within a specific class.
     * Used when the user's question targets one specific method.
     *
     * @property className Simple name of the containing class.
     * @property methodName Simple name of the method.
     */
    data class SingleMethod(val className: String, val methodName: String) : DetectionScope()

    /**
     * Detect a single class, optionally including its methods.
     *
     * @property className Simple name of the class.
     * @property includeMethods If true, also detect all methods within the class.
     */
    data class SingleClass(val className: String, val includeMethods: Boolean = false) : DetectionScope()
}
