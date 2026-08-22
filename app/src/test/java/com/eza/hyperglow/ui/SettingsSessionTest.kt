package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSessionTest {

    private class RecordingStore : SettingsSession.Store {
        val persistedDocuments = mutableListOf<CustomizationDocument>()
        val persistedConfigs = mutableListOf<AodRenderConfig>()
        val diagnosticWrites = mutableListOf<Boolean>()
        val published = mutableListOf<CustomizationDocument>()
        var documentPersistDelayMs = 0L
        var failDocumentPersist = false
        var failDiagnosticPersist = false

        override suspend fun persistDocument(document: CustomizationDocument): Boolean {
            if (documentPersistDelayMs > 0) delay(documentPersistDelayMs)
            if (failDocumentPersist) return false
            persistedDocuments += document
            return true
        }

        override suspend fun persistConfig(config: AodRenderConfig): Boolean {
            persistedConfigs += config
            return true
        }

        override suspend fun persistDiagnostic(enabled: Boolean): Boolean {
            if (failDiagnosticPersist) return false
            diagnosticWrites += enabled
            return true
        }

        override suspend fun publish(
            config: AodRenderConfig,
            document: CustomizationDocument,
            diagnosticLogging: Boolean
        ) {
            published += document
        }
    }

    private fun TestScope.createSession(store: RecordingStore): SettingsSession = SettingsSession(
        initialConfig = AodRenderConfig(),
        initialDocument = SceneCompiler.safeDefaultDocument(),
        initialDiagnosticLogging = false,
        initialCapabilityReport = com.eza.hyperglow.aod.StoredXiaomiCapabilityReport(),
        scope = backgroundScope,
        store = store,
        debounceMs = SETTINGS_FLUSH_DEBOUNCE_MS,
        nowMs = { testScheduler.currentTime }
    )

    // Virtual-time driver: delayed worker continuations only fire when the clock crosses their
    // deadline, so every wait below advances past it explicitly instead of relying on idleness.
    private fun TestScope.settleQuietWindow() {
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 1)
    }

    private fun CustomizationDocument.aodTextSize(): Int =
        profiles[SceneCompiler.SURFACE_AOD]?.textSizeCustom ?: -1

    private fun SettingsSession.setTextSize(value: Int) {
        updateSelectedProfile(SceneCompiler.SURFACE_AOD) {
            it.copy(textSize = "custom", textSizeCustom = value)
        }
    }

    @Test
    fun burstOfEditsCoalescesIntoSingleDebouncedFlushWithFinalValue() = runTest {
        val store = RecordingStore()
        val session = createSession(store)

        repeat(6) { index -> session.setTextSize(100 + index * 5) }
        assertEquals(0, store.persistedDocuments.size)

        settleQuietWindow()

        assertEquals(1, store.persistedDocuments.size)
        assertEquals(125, store.persistedDocuments.single().aodTextSize())
        assertEquals(1, store.published.size)
        assertEquals(125, store.published.single().aodTextSize())
        session.dispose()
    }

    @Test
    fun lateArrivalInsideDebounceWindowExtendsWaitAndKeepsLatestValueOnly() = runTest {
        val store = RecordingStore()
        val session = createSession(store)

        session.setTextSize(105)
        advanceTimeBy(100)
        assertEquals(0, store.persistedDocuments.size)

        session.setTextSize(110)
        // The quiet window now ends 150 ms after the latest edit, not after the first one.
        advanceTimeBy(149)
        assertEquals(0, store.persistedDocuments.size)

        advanceTimeBy(2)
        assertEquals(1, store.persistedDocuments.size)
        assertEquals(110, store.persistedDocuments.single().aodTextSize())
        session.dispose()
    }

    @Test
    fun mutationDuringPersistSkipsStalePublicationAndFollowUpFlushesNewerState() = runTest {
        val store = RecordingStore().apply { documentPersistDelayMs = 500L }
        val session = createSession(store)

        session.setTextSize(105)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 1)

        // Newer user edit lands while the older snapshot is still being written out.
        session.setTextSize(110)
        advanceTimeBy(10_000)

        assertEquals(listOf(105, 110), store.persistedDocuments.map { it.aodTextSize() })
        assertEquals(
            "The overtaken snapshot must never publish after its replacement",
            listOf(110),
            store.published.map { it.aodTextSize() }
        )
        assertEquals(110, session.document.value.aodTextSize())
        session.dispose()
    }

    @Test
    fun rapidStepperBurstCannotReorderOrRepublishIntermediateValues() = runTest {
        val store = RecordingStore()
        val session = createSession(store)

        var value = 100
        repeat(10) {
            value += 5
            session.setTextSize(value)
            // Taps land well inside one debounce window, so no intermediate value flushes.
            advanceTimeBy(20)
        }
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 200)

        assertEquals(listOf(150), store.persistedDocuments.map { it.aodTextSize() })
        assertEquals(listOf(150), store.published.map { it.aodTextSize() })
        assertEquals(150, session.document.value.aodTextSize())
        session.dispose()
    }

    @Test
    fun persistenceFailureRollsBackUnchangedOptimisticStateAndEmitsEvent() = runTest {
        val store = RecordingStore().apply { failDocumentPersist = true }
        val session = createSession(store)
        val failures = mutableListOf<SettingsPersistFailure>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.persistFailures.collect { failures += it }
        }

        session.setTextSize(120)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 10_000)

        assertEquals(
            "Memory returns to the last persisted document when nothing newer arrived",
            100,
            session.document.value.aodTextSize()
        )
        assertEquals(listOf(SettingsPersistFailure.DOCUMENT), failures)
        session.dispose()
    }

    @Test
    fun persistenceFailureKeepsNewerUserEditsMadeDuringTheFailedFlush() = runTest {
        val store = RecordingStore().apply {
            failDocumentPersist = true
            documentPersistDelayMs = 500L
        }
        val session = createSession(store)
        val failures = mutableListOf<SettingsPersistFailure>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.persistFailures.collect { failures += it }
        }

        session.setTextSize(105)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 1)
        // Newer edit lands during the failing flush and queues its retry.
        session.setTextSize(110)
        advanceTimeBy(600)
        // The retry is now the write in flight; letting it succeed proves the newer edits
        // survived the failed attempt instead of being rolled back.
        store.failDocumentPersist = false
        advanceTimeBy(10_000)

        assertEquals(
            "A failed flush must not roll back newer edits that arrived meanwhile",
            110,
            session.document.value.aodTextSize()
        )
        assertEquals(listOf(SettingsPersistFailure.DOCUMENT), failures)
        assertEquals(listOf(110), store.persistedDocuments.map { it.aodTextSize() })
        assertEquals(listOf(110), store.published.map { it.aodTextSize() })
        session.dispose()
    }

    @Test
    fun failedFlushLeavesDirtyFlagsSetSoNextMutationRetriesPersistence() = runTest {
        val store = RecordingStore().apply { failDocumentPersist = true }
        val session = createSession(store)

        session.setTextSize(105)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 10_000)
        assertTrue(store.persistedDocuments.isEmpty())
        store.failDocumentPersist = false
        session.setTextSize(110)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 10_000)

        assertEquals(listOf(110), store.persistedDocuments.map { it.aodTextSize() })
        assertEquals(listOf(110), store.published.map { it.aodTextSize() })
        assertEquals(110, session.document.value.aodTextSize())
        session.dispose()
    }

    @Test
    fun configOnlyBurstPersistsOnceWithoutPublishingWhenNothingPublishedChanged() = runTest {
        val store = RecordingStore()
        val session = createSession(store)

        session.updateConfig { it.copy(keepAwake = false) }
        session.updateConfig { it.copy(burnInIntervalMs = 120_000L) }
        settleQuietWindow()

        assertEquals(1, store.persistedConfigs.size)
        assertEquals(false, store.persistedConfigs.single().keepAwake)
        assertEquals(0, store.published.size)
        session.dispose()
    }

    @Test
    fun mixedDocAndConfigBurstFlushesTogetherAndPublishesOnce() = runTest {
        val store = RecordingStore()
        val session = createSession(store)

        session.updateSurfaceEnabled(SceneCompiler.SURFACE_AOD, false)
        session.updatePublishedConfig { it.copy(pauseLingerMs = 30_000L) }
        session.updateConfig { it.copy(keepAwakeUnsynced = true) }
        settleQuietWindow()

        assertEquals(1, store.persistedConfigs.size)
        assertEquals(true, store.persistedConfigs.single().keepAwakeUnsynced)
        assertEquals(30_000L, store.persistedConfigs.single().pauseLingerMs)
        // The surface toggle lives in the document; the config mirror only moves when the
        // real store mirrors the document into the legacy preference file.
        assertEquals(
            false,
            store.persistedDocuments.single().profiles[SceneCompiler.SURFACE_AOD]?.enabled
        )
        assertEquals(false, session.document.value.profiles.getValue(SceneCompiler.SURFACE_AOD).enabled)
        assertEquals(1, store.published.size)
        session.dispose()
    }

    @Test
    fun diagnosticChangePersistsAndPublishesThroughTheSameDebouncedFlush() = runTest {
        val store = RecordingStore()
        val session = createSession(store)

        session.setDiagnosticLogging(true)
        session.setDiagnosticLogging(false)
        session.setDiagnosticLogging(true)
        settleQuietWindow()

        assertEquals(listOf(true), store.diagnosticWrites)
        assertEquals(1, store.published.size)
        session.dispose()
    }

    @Test
    fun restoreAdoptsBothStoresAndAwaitsSynchronousFlush() = runTest {
        val store = RecordingStore()
        val session = createSession(store)
        val restoredDocument = SceneCompiler.safeDefaultDocument().let { document ->
            val profiles = document.profiles.toMutableMap()
            profiles[SceneCompiler.SURFACE_AOD] =
                profiles.getValue(SceneCompiler.SURFACE_AOD).copy(textSizeCustom = 180)
            document.copy(profiles = profiles)
        }

        session.restore(
            AodRenderConfig(keepAwake = false, hideLauncherIcon = true),
            restoredDocument
        )
        val persisted = session.flushNow()

        assertTrue(persisted)
        assertEquals(180, session.document.value.aodTextSize())
        assertEquals(false, session.config.value.keepAwake)
        assertEquals(180, store.persistedDocuments.last().aodTextSize())
        assertEquals(180, store.published.last().aodTextSize())
        session.dispose()
    }

    @Test
    fun diagnosticPersistenceFailureRollsBackFlagAndEmitsDiagnosticEvent() = runTest {
        val store = RecordingStore().apply { failDiagnosticPersist = true }
        val session = createSession(store)
        val failures = mutableListOf<SettingsPersistFailure>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.persistFailures.collect { failures += it }
        }

        session.setDiagnosticLogging(true)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 10_000)

        assertEquals(
            "The optimistic switch flip returns to the persisted value when the write fails",
            false,
            session.diagnosticLogging.value
        )
        assertEquals(emptyList<Boolean>(), store.diagnosticWrites)
        assertEquals(0, store.published.size)
        assertEquals(listOf(SettingsPersistFailure.DIAGNOSTIC), failures)
        session.dispose()
    }

    @Test
    fun documentFailureTakesPrecedenceAndRollsBackAllUnchangedTargetsTogether() = runTest {
        val store = RecordingStore().apply { failDocumentPersist = true }
        val session = createSession(store)
        val failures = mutableListOf<SettingsPersistFailure>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.persistFailures.collect { failures += it }
        }

        session.setTextSize(120)
        session.setDiagnosticLogging(true)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 10_000)

        assertEquals(listOf(SettingsPersistFailure.DOCUMENT), failures)
        assertEquals("The failed target rolls back", 100,
            session.document.value.aodTextSize())
        assertEquals(
            "The target whose write landed keeps its optimistic value",
            true,
            session.diagnosticLogging.value
        )
        assertEquals(listOf(true), store.diagnosticWrites)
        session.dispose()
    }

    @Test
    fun disposedSessionRejectsMutationsAndDropsPendingWork() = runTest {
        val store = RecordingStore()
        val session = createSession(store)

        session.setTextSize(105)
        session.dispose()
        session.setTextSize(110)
        advanceTimeBy(SETTINGS_FLUSH_DEBOUNCE_MS + 10_000)

        assertEquals(0, store.persistedDocuments.size)
        assertEquals(0, store.published.size)
        // The pre-dispose edit stays in memory; nothing further is accepted or flushed.
        assertEquals(105, session.document.value.aodTextSize())
    }
}
