package com.eza.hyperglow.root

import java.lang.reflect.Field

/**
 * Resolves a field by walking the superclass chain instead of stopping at the class that was named.
 *
 * A ROM refactor that hoists a field into a base class leaves it perfectly readable at runtime, but
 * `getDeclaredField` on the subclass reports it absent — and an absent field is indistinguishable
 * from a ROM that never had the feature. `XiaomiCapabilityResolver.hasField` probes the same way, so
 * every read site must agree with it: a probe that says a capability is present while its read site
 * fails is worse than not advertising the capability at all.
 *
 * Deliberately keyed on the field, not on its value. A field that exists and legitimately holds null
 * must resolve here, or a null value reads as a missing symbol.
 *
 * Methods are the opposite case and must not walk — see `XiaomiCapabilityResolver.hasMethod`.
 */
internal fun hierarchyField(type: Class<*>, name: String): Field? {
    var current: Class<*>? = type
    while (current != null) {
        val declaring = current
        val field = runCatching { declaring.getDeclaredField(name) }.getOrNull()
        if (field != null) {
            runCatching { field.isAccessible = true }
            return field
        }
        current = declaring.superclass
    }
    return null
}

/** Reads [name] off [owner], resolving the field anywhere in its hierarchy. */
internal fun readHierarchyField(owner: Any?, name: String): Any? {
    owner ?: return null
    val field = hierarchyField(owner.javaClass, name) ?: return null
    return runCatching { field.get(owner) }.getOrNull()
}
