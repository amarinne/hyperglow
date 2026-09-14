package com.eza.hyperglow.root.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ResolverSubject {
    @JvmField var value: Int = 0
    fun event() = Unit
    fun event(flag: Boolean) = flag
}

class SymbolResolverTest {
    private val loader = javaClass.classLoader!!
    private val owner = ResolverSubject::class.java.name

    @Before
    fun clearCaches() {
        SymbolResolver.clearCaches()
        SymbolSourceLog.reset()
    }

    @Test
    fun bundledHitsNeverOpenDexKit() {
        val before = SymbolResolver.stats().dexKitQueries

        assertNotNull(SymbolResolver.resolveMethod(loader, "test", SymbolRequest.method(owner, "event")))
        assertNotNull(SymbolResolver.resolveField(loader, "test", SymbolRequest.field(owner, "value")))

        assertEquals(before, SymbolResolver.stats().dexKitQueries)
    }

    @Test
    fun methodMissAttemptsDexKitAndRetriesWhenBridgeIsUnavailable() {
        val request = SymbolRequest.method(owner, "missing")
        val before = SymbolResolver.stats().dexKitQueries

        assertNull(SymbolResolver.resolveMethod(loader, "test", request))
        assertNull(SymbolResolver.resolveMethod(loader, "test", request))

        assertEquals(before + 2, SymbolResolver.stats().dexKitQueries)
    }

    @Test
    fun fieldMissAttemptsDexKitAndRetriesWhenBridgeIsUnavailable() {
        val request = SymbolRequest.field(owner, "missing")
        val before = SymbolResolver.stats().dexKitQueries

        assertNull(SymbolResolver.resolveField(loader, "test", request))
        assertNull(SymbolResolver.resolveField(loader, "test", request))

        assertEquals(before + 2, SymbolResolver.stats().dexKitQueries)
    }

    @Test
    fun frameworkMissIsCachedWithoutDexKit() {
        val request = SymbolRequest.method("java.lang.String", "missing")
        val before = SymbolResolver.stats()

        assertNull(SymbolResolver.resolveMethod(loader, "test", request))
        assertNull(SymbolResolver.resolveMethod(loader, "test", request))

        val after = SymbolResolver.stats()
        assertEquals(before.bundledMisses + 1, after.bundledMisses)
        assertEquals(before.dexKitQueries, after.dexKitQueries)
    }

    @Test
    fun missingParameterClassDisablesOnlyThatSymbol() {
        val missing = SymbolRequest.method(owner, "event", "no.such.Type")

        assertNull(SymbolResolver.resolveMethod(loader, "test", missing))
        val actual = SymbolResolver.resolveMethod(loader, "test", SymbolRequest.method(owner, "event"))

        assertNotNull(actual)
        assertEquals(0, actual!!.parameterCount)
    }

    @Test(expected = OutOfMemoryError::class)
    fun fatalLoaderFailurePropagates() {
        val failingLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> = throw OutOfMemoryError("test fixture")
        }

        SymbolResolver.resolveMethod(failingLoader, "test", SymbolRequest.method(owner, "event"))
    }

    @Test
    fun classMissAttemptsDexKitWhileBridgeIsUnavailable() {
        val missingLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> = throw ClassNotFoundException(name)
        }
        val before = SymbolResolver.stats()

        assertNull(SymbolResolver.resolveClass(missingLoader, "test", "no.such.Class"))

        assertEquals(before.dexKitQueries + 1, SymbolResolver.stats().dexKitQueries)
    }

    @Test
    fun provenanceCountsOnlySuccessfulSourcesAndSumsSubMillisecondBridgeTime() {
        SymbolSourceLog.record("test", "missing", false, SymbolSource.DEXKIT)
        SymbolSourceLog.record("test", "present", true, SymbolSource.BUNDLED)
        SymbolSourceLog.bridgeNanos(600_000)
        SymbolSourceLog.bridgeNanos(600_000)

        assertEquals(1, SymbolSourceLog.bridgeTotal())
        assertTrue(SymbolSourceLog.summary().startsWith("bundled=1 dexkit=0 miss=1 bridgeMs=1"))
    }
}
