package com.eza.hyperglow.root.symbols

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Neither a loader key nor a reflected member value may keep a retired plugin alive. */
internal class SymbolCache<T : Any> {
    data class Entry<T>(val value: T?)

    private val entries = WeakHashMap<ClassLoader, HashMap<SymbolRequest, WeakReference<T>?>>()

    @Synchronized
    fun read(loader: ClassLoader, request: SymbolRequest): Entry<T>? {
        val symbols = entries[loader] ?: return null
        if (!symbols.containsKey(request)) return null
        val reference = symbols[request] ?: return Entry(null)
        val value = reference.get()
        if (value == null) {
            symbols.remove(request)
            return null
        }
        return Entry(value)
    }

    @Synchronized
    fun write(loader: ClassLoader, request: SymbolRequest, value: T?) {
        entries.getOrPut(loader) { HashMap() }[request] = value?.let { WeakReference(it) }
    }

    @Synchronized
    fun clear() = entries.clear()
}
