package com.eza.hyperglow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTraceFileTest {
    @Test
    fun traceRotatesOnlyAtTheBound() {
        assertFalse(shouldRotateTrace(0L, DiagnosticTraceFile.MAX_BYTES))
        assertFalse(shouldRotateTrace(DiagnosticTraceFile.MAX_BYTES - 1L, DiagnosticTraceFile.MAX_BYTES))
        assertTrue(shouldRotateTrace(DiagnosticTraceFile.MAX_BYTES, DiagnosticTraceFile.MAX_BYTES))
        assertTrue(shouldRotateTrace(DiagnosticTraceFile.MAX_BYTES * 2, DiagnosticTraceFile.MAX_BYTES))
    }
}
