package com.eza.hyperglow.root.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SymbolCacheTest {
    private val request = SymbolRequest.method("test.Owner", "event")
    private val loader = javaClass.classLoader!!

    @Test
    fun cachedMissIsDifferentFromUnobservedSymbol() {
        val cache = SymbolCache<String>()
        assertNull(cache.read(loader, request))

        cache.write(loader, request, null)

        assertNotNull(cache.read(loader, request))
        assertNull(cache.read(loader, request)!!.value)
    }

    @Test
    fun loadersDoNotShareMissesAndClearAllowsFreshLookup() {
        val cache = SymbolCache<String>()
        val anotherLoader = object : ClassLoader(loader) {}
        cache.write(loader, request, null)
        cache.write(anotherLoader, request, "resolved")

        assertNull(cache.read(loader, request)!!.value)
        assertEquals("resolved", cache.read(anotherLoader, request)!!.value)

        cache.clear()
        assertNull(cache.read(loader, request))
        assertNull(cache.read(anotherLoader, request))
    }
}
