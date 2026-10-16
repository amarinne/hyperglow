package com.eza.hyperglow.root.symbols

import com.eza.hyperglow.root.hookIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * One seam with two shapes, the way a signature that moved between ROM generations looks:
 * HyperOS 3 declared `doAnimationToAod(boolean, boolean, boolean)` and HyperOS 4 declares the
 * five-argument form of the same name.
 */
private class CandidateSubject {
    fun morph(toAod: Boolean, flag: Boolean) = toAod
    fun morph(toAod: Boolean, flag: Boolean, extra: Boolean) = toAod
}

class SymbolResolverOrderTest {
    private val loader = javaClass.classLoader!!
    private val owner = CandidateSubject::class.java.name

    @Before
    fun clearCaches() {
        SymbolResolver.clearCaches()
        SymbolSourceLog.reset()
    }

    @Test
    fun firstResolvingCandidateWins() {
        val wide = SymbolRequest.method(owner, "morph", "boolean", "boolean", "boolean")
        val narrow = SymbolRequest.method(owner, "morph", "boolean", "boolean")

        assertEquals(
            3,
            SymbolResolver.resolveMethodInOrder(loader, "test", listOf(wide, narrow))!!.parameterCount
        )
        assertEquals(
            2,
            SymbolResolver.resolveMethodInOrder(loader, "test", listOf(narrow, wide))!!.parameterCount
        )
    }

    @Test
    fun missingCandidateFallsThroughToTheNext() {
        val absent = SymbolRequest.method(owner, "absent", "boolean")
        val present = SymbolRequest.method(owner, "morph", "boolean", "boolean")

        assertNotNull(SymbolResolver.resolveMethodInOrder(loader, "test", listOf(absent, present)))
    }

    @Test
    fun allCandidatesMissingResolvesToNull() {
        val first = SymbolRequest.method(owner, "absent")
        val second = SymbolRequest.method(owner, "alsoAbsent")

        assertNull(SymbolResolver.resolveMethodInOrder(loader, "test", listOf(first, second)))
    }

    /**
     * The application-class candidates are resolved as class-plus-member pairs, so a candidate whose
     * class is missing must not stop a later candidate from resolving.
     */
    @Test
    fun candidateWithMissingClassFallsThroughToTheNextOwner() {
        val firstOwner = SymbolRequest.method("no.such.Class", "onCreate")
        val secondOwner = SymbolRequest.method(owner, "morph", "boolean", "boolean")

        assertNotNull(SymbolResolver.resolveMethodInOrder(loader, "test", listOf(firstOwner, secondOwner)))
        assertNull(
            SymbolResolver.resolveMethodInOrder(
                loader,
                "test",
                listOf(firstOwner, SymbolRequest.method("also.Missing", "onCreate"))
            )
        )
    }

    /**
     * The clock morph and the linkage fallback can both bind `doAnimationToAod` on one build. Hook
     * ids are keyed by feature as well as target, so the second install replaces nothing.
     */
    @Test
    fun sameTargetKeepsDistinctHookIdsPerFeature() {
        val target = CandidateSubject::class.java.getDeclaredMethod(
            "morph",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )

        assertNotEquals(hookIdFor("clock-morph", target), hookIdFor("linkage", target))
    }
}
