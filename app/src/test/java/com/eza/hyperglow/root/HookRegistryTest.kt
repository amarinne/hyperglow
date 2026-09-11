package com.eza.hyperglow.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HookRegistryTest {

    @Test
    fun sameTargetProducesSameIdAcrossLookups() {
        val first = String::class.java.getDeclaredMethod("substring", Int::class.javaPrimitiveType)
        val second = String::class.java.getDeclaredMethod("substring", Int::class.javaPrimitiveType)

        assertEquals(hookIdFor("aod-surface", first), hookIdFor("aod-surface", second))
    }

    @Test
    fun overloadsProduceDistinctIds() {
        val single = String::class.java.getDeclaredMethod("substring", Int::class.javaPrimitiveType)
        val ranged = String::class.java.getDeclaredMethod(
            "substring",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        assertNotEquals(hookIdFor("aod-surface", single), hookIdFor("aod-surface", ranged))
    }

    @Test
    fun constructorIdDiffersFromMethodId() {
        val constructor = StringBuilder::class.java.getDeclaredConstructor(String::class.java)
        val method = StringBuilder::class.java.getDeclaredMethod("append", String::class.java)

        val constructorId = hookIdFor("aod-surface", constructor)
        val methodId = hookIdFor("aod-surface", method)

        assertNotEquals(constructorId, methodId)
        assertEquals(
            "hyperglow:aod-surface:java.lang.StringBuilder#<init>(java.lang.String)",
            constructorId
        )
    }

    @Test
    fun sameTargetUnderDifferentFeaturesProducesDistinctIds() {
        val target = String::class.java.getDeclaredMethod("length")

        assertNotEquals(hookIdFor("aod-surface", target), hookIdFor("lockscreen", target))
    }
}
