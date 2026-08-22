package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.StoredXiaomiCapabilityReport
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which persistence target rejected a flush; the UI maps this to a user-visible toast. */
internal enum class SettingsPersistFailure { DOCUMENT, CONFIG, DIAGNOSTIC }

/**
 * Activity-owned settings state holder.
 *
 * Owns the in-memory render configuration, customization document, diagnostic flag, and capability
 * report for the whole settings UI, so screens observe immutable snapshots instead of reading disk
 * during composition. Mutations update memory immediately (optimistic UI) and request one
 * conflated background flush; a burst of edits persists and publishes once, [debounceMs] after the
 * burst settles.
 *
 * Thread ownership: mutations run on the main thread. The single worker coroutine owns flushing;
 * [flushNow] shares its mutex, so persisted/published output stays serially ordered. Every
 * publication rechecks the mutation generation captured at snapshot time: work overtaken by newer
 * edits skips publishing and leaves the state to the queued follow-up flush, and a disposed or
 * cancelled scope drops pending work entirely. Store implementations must catch their own I/O
 * errors and report them as `false`; they are never expected to throw.
 */
internal class SettingsSession(
    initialConfig: AodRenderConfig,
    initialDocument: CustomizationDocument,
    initialDiagnosticLogging: Boolean,
    initialCapabilityReport: StoredXiaomiCapabilityReport,
    private val scope: CoroutineScope,
    private val store: Store,
    private val debounceMs: Long = SETTINGS_FLUSH_DEBOUNCE_MS,
    private val nowMs: () -> Long
) {
    internal interface Store {
        suspend fun persistDocument(document: CustomizationDocument): Boolean
        suspend fun persistConfig(config: AodRenderConfig): Boolean

        /** Persists the diagnostic preference; on success it applies process side effects too. */
        suspend fun persistDiagnostic(enabled: Boolean): Boolean

        /**
         * Publishes the compiled configuration to SystemUI through the app-process bridge.
         *
         * Suspending so the flush can guarantee the Binder publish never lands on the UI thread
         * even when [flushNow] is awaited from a main-scope coroutine.
         */
        suspend fun publish(
            config: AodRenderConfig,
            document: CustomizationDocument,
            diagnosticLogging: Boolean
        )
    }

    private val lock = Any()

    private val _config = MutableStateFlow(initialConfig)
    val config: StateFlow<AodRenderConfig> = _config.asStateFlow()

    private val _document = MutableStateFlow(initialDocument)
    val document: StateFlow<CustomizationDocument> = _document.asStateFlow()

    private val _diagnosticLogging = MutableStateFlow(initialDiagnosticLogging)
    val diagnosticLogging: StateFlow<Boolean> = _diagnosticLogging.asStateFlow()

    private val _capabilityReport = MutableStateFlow(initialCapabilityReport)
    val capabilityReport: StateFlow<StoredXiaomiCapabilityReport> = _capabilityReport.asStateFlow()

    private val _persistFailures = MutableSharedFlow<SettingsPersistFailure>(
        extraBufferCapacity = PERSIST_FAILURE_BUFFER
    )
    val persistFailures: SharedFlow<SettingsPersistFailure> = _persistFailures.asSharedFlow()

    private var generation = 0L
    private var documentDirty = false
    private var configDirty = false
    private var diagnosticDirty = false
    private var publishPending = false
    private var lastRequestAtMs = 0L

    private var persistedConfig: AodRenderConfig = initialConfig
    private var persistedDocument: CustomizationDocument = initialDocument
    private var persistedDiagnostic: Boolean = initialDiagnosticLogging

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private val flushMutex = Mutex()

    @Volatile
    private var disposed = false

    /**
     * Forces an immediate flush of the current state and waits for it.
     *
     * Used by import/restore paths whose user feedback depends on the write having landed.
     */
    suspend fun flushNow(): Boolean {
        if (disposed) return false
        return flushMutex.withLock { if (disposed) false else doFlush() }
    }

    /** Stops accepting mutations and cancels pending debounced work. */
    fun dispose() {
        disposed = true
        synchronized(lock) {
            worker?.cancel()
            worker = null
            requests.close()
        }
    }

    fun updateSurfaceEnabled(surface: String, enabled: Boolean) {
        mutateDocument(surface) { it.copy(enabled = enabled) }
    }

    fun updateSelectedProfile(surface: String, transform: (SurfaceProfile) -> SurfaceProfile) {
        mutateDocument(surface, transform)
    }

    /** Legacy linked-surface documents are unlinked in memory on first editor open. */
    fun disableLinkSurfaces() {
        synchronized(lock) {
            if (!_document.value.linkSurfaces || disposed) return@synchronized
            _document.value = _document.value.copy(linkSurfaces = false)
            markDirtyLocked(documentDirty = true, publishes = true)
        }
    }

    fun resetDocument() {
        synchronized(lock) {
            if (disposed) return@synchronized
            _document.value = SceneCompiler.safeDefaultDocument()
            markDirtyLocked(documentDirty = true, publishes = true)
        }
    }

    /**
     * Adopts an imported configuration backup optimistically; the caller awaits [flushNow] before
     * reporting success so a failed restore can be surfaced while the failure event rolls memory
     * back.
     */
    fun restore(preferences: AodRenderConfig, document: CustomizationDocument?) {
        synchronized(lock) {
            if (disposed) return@synchronized
            _config.value = preferences
            document?.let { _document.value = it }
            markDirtyLocked(
                documentDirty = document != null,
                configDirty = true,
                publishes = true
            )
        }
    }

    /** Config change that SystemUI reads through projection state rather than published config. */
    fun updateConfig(transform: (AodRenderConfig) -> AodRenderConfig) {
        mutateConfig(transform, publishes = false)
    }

    /** Config change carried inside the compiled configuration pushed to SystemUI. */
    fun updatePublishedConfig(transform: (AodRenderConfig) -> AodRenderConfig) {
        mutateConfig(transform, publishes = true)
    }

    fun setDiagnosticLogging(enabled: Boolean) {
        synchronized(lock) {
            if (_diagnosticLogging.value == enabled || disposed) return@synchronized
            _diagnosticLogging.value = enabled
            markDirtyLocked(diagnosticDirty = true, publishes = true)
        }
    }

    fun updateCapabilityReport(report: StoredXiaomiCapabilityReport) {
        _capabilityReport.value = report
    }

    private fun mutateDocument(
        surface: String,
        transform: (SurfaceProfile) -> SurfaceProfile
    ) {
        synchronized(lock) {
            if (disposed) return@synchronized
            val profiles = _document.value.profiles.toMutableMap()
            profiles[surface] = transform(profiles[surface] ?: SurfaceProfile())
            _document.value = _document.value.copy(profiles = profiles)
            markDirtyLocked(documentDirty = true, publishes = true)
        }
    }

    private fun mutateConfig(
        transform: (AodRenderConfig) -> AodRenderConfig,
        publishes: Boolean
    ) {
        synchronized(lock) {
            if (disposed) return@synchronized
            _config.value = transform(_config.value)
            markDirtyLocked(configDirty = true, publishes = publishes)
        }
    }

    /** Caller holds [lock]. */
    private fun markDirtyLocked(
        documentDirty: Boolean = false,
        configDirty: Boolean = false,
        diagnosticDirty: Boolean = false,
        publishes: Boolean
    ) {
        this.documentDirty = this.documentDirty || documentDirty
        this.configDirty = this.configDirty || configDirty
        this.diagnosticDirty = this.diagnosticDirty || diagnosticDirty
        publishPending = publishPending || publishes
        generation++
        lastRequestAtMs = nowMs()
        if (disposed) return
        requests.trySend(Unit)
        ensureWorkerLocked()
    }

    private fun ensureWorkerLocked() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (signal in requests) {
                if (disposed) return@launch
                awaitQuietPeriod()
                if (disposed) return@launch
                flushMutex.withLock {
                    if (!disposed) doFlush()
                }
            }
        }
    }

    /**
     * Debounces relative to the latest edit: sleeps only the still-missing remainder of the
     * quiet window, so a burst flushes exactly one debounce interval after its last mutation.
     */
    private suspend fun awaitQuietPeriod() {
        while (true) {
            val (observedGeneration, lastRequestAt) = synchronized(lock) {
                generation to lastRequestAtMs
            }
            val elapsedSinceLastEdit = (nowMs() - lastRequestAt).coerceAtLeast(0L)
            val remaining = debounceMs - elapsedSinceLastEdit
            if (remaining > 0) {
                delay(remaining)
            }
            if (disposed || synchronized(lock) { generation } == observedGeneration) return
        }
    }

    private suspend fun doFlush(): Boolean {
        val captured = synchronized(lock) {
            CapturedFlush(
                generation = generation,
                config = _config.value,
                document = _document.value,
                diagnosticLogging = _diagnosticLogging.value,
                documentDirty = documentDirty,
                configDirty = configDirty,
                diagnosticDirty = diagnosticDirty,
                publishPending = publishPending
            )
        }
        var documentOk = true
        var configOk = true
        var diagnosticOk = true
        // Each target records its own success so a partial failure rolls back exactly the
        // targets whose writes did not land, not the ones that did.
        if (captured.documentDirty) {
            documentOk = store.persistDocument(captured.document)
            if (documentOk) persistedDocument = captured.document
        }
        if (captured.configDirty) {
            configOk = store.persistConfig(captured.config)
            if (configOk) persistedConfig = captured.config
        }
        if (captured.diagnosticDirty) {
            diagnosticOk = store.persistDiagnostic(captured.diagnosticLogging)
            if (diagnosticOk) persistedDiagnostic = captured.diagnosticLogging
        }
        if (!documentOk || !configOk || !diagnosticOk) {
            onPersistFailed(captured, documentOk, configOk)
            return false
        }
        synchronized(lock) {
            // Only clear flags when no newer edit arrived mid-flush; a newer edit set them again
            // and its own queued flush covers the delta.
            if (generation == captured.generation) {
                documentDirty = false
                configDirty = false
                diagnosticDirty = false
                publishPending = false
            } else {
                requests.trySend(Unit)
            }
        }
        if (!captured.publishPending) return true
        // Final current-state check: skip publishing stale values after replacement; the queued
        // follow-up flush publishes the newer state instead.
        val current = synchronized(lock) {
            if (generation != captured.generation) {
                null
            } else {
                PublishedSnapshot(_config.value, _document.value)
            }
        } ?: return true
        store.publish(current.config, current.document, captured.diagnosticLogging)
        return true
    }

    private suspend fun onPersistFailed(
        captured: CapturedFlush,
        documentOk: Boolean,
        configOk: Boolean
    ) {
        synchronized(lock) {
            // Roll back optimistic memory only when no newer edit arrived during the failed
            // flush; otherwise keep the newer edits and let their retry attempt cover them.
            if (generation == captured.generation) {
                _config.value = persistedConfig
                _document.value = persistedDocument
                _diagnosticLogging.value = persistedDiagnostic
            }
        }
        val target = when {
            !documentOk -> SettingsPersistFailure.DOCUMENT
            !configOk -> SettingsPersistFailure.CONFIG
            else -> SettingsPersistFailure.DIAGNOSTIC
        }
        _persistFailures.emit(target)
    }

    private data class CapturedFlush(
        val generation: Long,
        val config: AodRenderConfig,
        val document: CustomizationDocument,
        val diagnosticLogging: Boolean,
        val documentDirty: Boolean,
        val configDirty: Boolean,
        val diagnosticDirty: Boolean,
        val publishPending: Boolean
    )

    private data class PublishedSnapshot(
        val config: AodRenderConfig,
        val document: CustomizationDocument
    )
}

/** The audit's proposed conflation window: long enough for bursts, short enough to feel live. */
internal const val SETTINGS_FLUSH_DEBOUNCE_MS = 150L

private const val PERSIST_FAILURE_BUFFER = 8

