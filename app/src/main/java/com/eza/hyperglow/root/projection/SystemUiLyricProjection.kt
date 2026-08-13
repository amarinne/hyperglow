package com.eza.hyperglow.root.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import com.eza.hyperglow.DiagnosticLoggingRuntime
import com.eza.hyperglow.aod.AodStateWireMessage
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.root.aod.AodLyricClient
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.lockscreen.RaiseToAodController
import com.eza.hyperglow.root.lockscreen.LockscreenEditorGestureController
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec.WirePayload
import java.util.IdentityHashMap

internal enum class LyricSurfaceKind { LOCKSCREEN, AOD }

internal const val LYRIC_SNAPSHOT_FRESH_MS = 5_000L
private const val MAX_WIRE_FUTURE_SKEW_MS = 1_000L

internal fun isPlausibleWireTimestamp(updatedAtElapsedMs: Long, nowElapsedMs: Long): Boolean =
    updatedAtElapsedMs >= 0L && nowElapsedMs >= 0L &&
        updatedAtElapsedMs - nowElapsedMs <= MAX_WIRE_FUTURE_SKEW_MS

internal fun currentProcessUserId(): Int =
    UserHandle.getUserHandleForUid(Process.myUid()).hashCode()

internal fun shouldRenewAodDraw(
    surfaceKind: LyricSurfaceKind,
    attached: Boolean,
    sceneActive: Boolean,
    effectivelyVisible: Boolean,
    pendingStockMotion: Boolean,
    keepAlive: Boolean
): Boolean = surfaceKind == LyricSurfaceKind.AOD &&
    attached &&
    sceneActive &&
    keepAlive &&
    (effectivelyVisible || pendingStockMotion)

internal fun shouldRequestAodWake(
    attached: Boolean,
    sceneActive: Boolean,
    effectivelyVisible: Boolean
): Boolean = attached && sceneActive && effectivelyVisible

internal interface SystemUiLyricSubscriber {
    val surfaceKind: LyricSurfaceKind

    fun onLyricSnapshot(snapshot: LyricSnapshot)

    fun onLyricKeepAlive(signal: LyricKeepAliveSignal) = Unit

    fun onLyricProjectionDisconnected() = Unit

    fun onLyricProjectionStale() = Unit

    fun onCustomization(configuration: CompiledCustomization) = Unit
}

internal interface LyricProjectionClient {
    fun bind(hostContext: Context?, userId: Int)

    fun unbind()

    fun reportCapabilities() = Unit
}

internal interface LyricExpiryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancel()
}

private class MainThreadLyricExpiryScheduler : LyricExpiryScheduler {
    private val handler = try {
        Handler(Looper.getMainLooper())
    } catch (_: Exception) {
        null
    }
    private var pending: Runnable? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
        cancel()
        val runnable = Runnable(action)
        pending = runnable
        handler?.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    override fun cancel() {
        pending?.let { handler?.removeCallbacks(it) }
        pending = null
    }
}

internal class SystemUiLyricProjection(
    private val expiryScheduler: LyricExpiryScheduler = MainThreadLyricExpiryScheduler(),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val processUserId: () -> Int = ::currentProcessUserId,
    private val setDiagnosticLogging: (Boolean) -> Unit = DiagnosticLoggingRuntime::setEnabled,
    private val setRaiseToAod: (Boolean) -> Unit = RaiseToAodController::setEnabled,
    private val setSuppressLockscreenEditorLongPress: (Boolean) -> Unit =
        LockscreenEditorGestureController::setEnabled,
    clientFactory: ((WirePayload) -> Unit, (AodStateWireMessage) -> Unit, () -> Unit) ->
        LyricProjectionClient = { onConfiguration, onState, onDisconnected ->
            AodLyricClient(onConfiguration, onState, onDisconnected)
        }
) {
    private val subscribers = IdentityHashMap<SystemUiLyricSubscriber, Unit>()
    private val client = clientFactory(::handleConfiguration, ::handleState, ::handleDisconnected)
    private var latestSnapshot: LyricSnapshot? = null
    private var latestVisibleSnapshot: LyricSnapshot? = null
    private var latestConfiguration: CompiledCustomization? = null
    private var lastRevision = -1L
    private var lastUpdatedAt = -1L
    private var bindingContext: Context? = null
    private var clientBound = false
    private var bootstrapped = false
    private var expectedUserId: Int? = null

    @Synchronized
    fun bootstrap(context: Context?) {
        bootstrapped = true
        if (context != null) {
            bindingContext = context
            expectedUserId = processUserId()
        }
        ensureBound()
        client.reportCapabilities()
    }

    @Synchronized
    fun attach(subscriber: SystemUiLyricSubscriber, context: Context?) {
        if (context != null) bindingContext = context
        subscribers[subscriber] = Unit
        ensureBound()
        latestConfiguration?.let(subscriber::onCustomization)
        latestSnapshot?.let(subscriber::onLyricSnapshot)
    }

    @Synchronized
    fun detach(subscriber: SystemUiLyricSubscriber) {
        if (subscribers.remove(subscriber) == null) return
        if (subscribers.isEmpty() && !bootstrapped) {
            client.unbind()
            clientBound = false
            clearCachedState()
        }
    }

    @Synchronized
    internal fun accept(message: LyricProjectionMessage): Boolean {
        if (expectedUserId?.let { it != message.userId } == true) return false
        if (message.revision < lastRevision) return false
        if (message.revision == lastRevision && message.updatedAtElapsedMs <= lastUpdatedAt) {
            return false
        }
        return when (message) {
            is LyricProjectionMessage.Snapshot -> {
                lastRevision = message.revision
                lastUpdatedAt = message.updatedAtElapsedMs
                latestSnapshot = message.value
                if (message.value.visible) latestVisibleSnapshot = message.value
                // Terminal hidden state clears the cached visible snapshot. Keeping it made the
                // cache an unbounded rebuild source: a surface attaching much later refilled its
                // own last-visible slot from here and could present a lyric whose session had
                // already ended.
                else if (message.value.isTerminalHidden()) latestVisibleSnapshot = null
                scheduleExpiry(message.value)
                subscribers.keys.toList().forEach { it.onLyricSnapshot(message.value) }
                true
            }
            is LyricProjectionMessage.KeepAlive -> {
                if (message.revision != lastRevision) return false
                val current = latestSnapshot ?: return false
                lastUpdatedAt = message.updatedAtElapsedMs
                latestSnapshot = current.copy(
                    updatedAtElapsedMs = message.updatedAtElapsedMs,
                    keepAlive = message.value.keepAlive,
                    wakeSignal = message.value.wakeSignal,
                    playbackActive = message.value.playbackActive,
                    pauseRetentionEligible = message.value.pauseRetentionEligible
                )
                if (current.visible) latestVisibleSnapshot = latestSnapshot
                latestSnapshot?.let(::scheduleExpiry)
                subscribers.keys.toList().forEach { it.onLyricKeepAlive(message.value) }
                true
            }
        }
    }

    @Synchronized
    internal fun subscriberCount(): Int = subscribers.size

    @Synchronized
    internal fun cachedSnapshot(): LyricSnapshot? = latestSnapshot

    @Synchronized
    internal fun cachedVisibleSnapshot(): LyricSnapshot? = latestVisibleSnapshot

    @Synchronized
    internal fun cachedCustomization(): CompiledCustomization? = latestConfiguration

    @Synchronized
    internal fun expireIfStale(nowElapsedMs: Long): Boolean {
        val snapshot = latestSnapshot ?: return false
        if ((!snapshot.visible && !snapshot.playbackActive) ||
            nowElapsedMs - snapshot.updatedAtElapsedMs <= LYRIC_SNAPSHOT_FRESH_MS
        ) {
            return false
        }
        latestSnapshot = null
        latestVisibleSnapshot = null
        expiryScheduler.cancel()
        subscribers.keys.toList().forEach(SystemUiLyricSubscriber::onLyricProjectionStale)
        return true
    }

    @Synchronized
    fun onUserChanged(userId: Int? = null) {
        expectedUserId = userId
        client.unbind()
        clientBound = false
        clearCachedState()
        subscribers.keys.toList().forEach(SystemUiLyricSubscriber::onLyricProjectionDisconnected)
        if (bootstrapped || subscribers.isNotEmpty()) ensureBound()
    }

    fun reportCapabilities() {
        client.reportCapabilities()
    }

    @Synchronized
    internal fun acceptConfiguration(configuration: CompiledCustomization): Boolean {
        setDiagnosticLogging(configuration.diagnosticLogging)
        setRaiseToAod(configuration.raiseToAod)
        setSuppressLockscreenEditorLongPress(configuration.suppressLockscreenEditorLongPress)
        val current = latestConfiguration
        if (current != null && current.revision == configuration.revision &&
            current.hash == configuration.hash
        ) return false
        latestConfiguration = configuration
        subscribers.keys.toList().forEach { it.onCustomization(configuration) }
        return true
    }

    private fun handleConfiguration(configuration: WirePayload) {
        val parsed = CompiledCustomizationBundleCodec.fromWirePayload(
            configuration,
            expectedUserId
        ) ?: return
        acceptConfiguration(parsed)
    }

    private fun handleState(state: AodStateWireMessage) {
        if (!isPlausibleWireTimestamp(state.updatedAtElapsedMs, elapsedRealtime())) return
        accept(state.toLyricProjectionMessage())
    }

    @Synchronized
    private fun handleDisconnected() {
        clearCachedState()
        subscribers.keys.toList().forEach(SystemUiLyricSubscriber::onLyricProjectionDisconnected)
    }

    private fun clearCachedState() {
        setDiagnosticLogging(false)
        setRaiseToAod(false)
        setSuppressLockscreenEditorLongPress(false)
        expiryScheduler.cancel()
        latestSnapshot = null
        latestVisibleSnapshot = null
        latestConfiguration = null
        lastRevision = -1L
        lastUpdatedAt = -1L
    }

    private fun ensureBound() {
        if (clientBound) return
        clientBound = true
        val userId = expectedUserId ?: processUserId()
        val source = if (expectedUserId != null) "tracker" else "process"
        HookLogger.bootstrap(
            "SystemUiProjection",
            "bridge_bind_user=${userId.coerceIn(-1, 99_999)} source=$source"
        )
        client.bind(
            hostContext = bindingContext,
            userId = userId
        )
    }

    private fun scheduleExpiry(snapshot: LyricSnapshot) {
        expiryScheduler.cancel()
        if (!snapshot.visible && !snapshot.playbackActive) return
        val expectedRevision = snapshot.revision
        val expectedUpdatedAt = snapshot.updatedAtElapsedMs
        val delay = (expectedUpdatedAt + LYRIC_SNAPSHOT_FRESH_MS -
            elapsedRealtime()).coerceAtLeast(0L) + 1L
        expiryScheduler.schedule(delay) {
            synchronized(this) {
                val current = latestSnapshot
                if (current?.revision == expectedRevision &&
                    current.updatedAtElapsedMs == expectedUpdatedAt
                ) {
                    expireIfStale(elapsedRealtime())
                }
            }
        }
    }
}

internal object SystemUiLyricProjectionRuntime {
    val projection = SystemUiLyricProjection()
}
