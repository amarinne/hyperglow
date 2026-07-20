package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.root.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

internal object AodPositionHook {
    private data class ControllerState(
        var lastStockTranslationX: Int? = null,
        var lastStockTranslationY: Float? = null,
        var managedStep: Int = -1,
        var currentManagedDecision: AodClockPlacementDecision? = null,
        var pendingManagedDecision: AodClockPlacementDecision? = null
    )

    private data class PositionResolution(val decision: AodClockPlacementDecision)

    private data class ManagedAdvance(
        val controller: Any,
        val previousStep: Int,
        val previousDecision: AodClockPlacementDecision?,
        val attemptedStep: Int,
        val attemptedDecision: AodClockPlacementDecision
    )

    private data class StockRestore(
        val controller: Any,
        val stockX: Int,
        val stockY: Float,
        val managedStep: Int,
        val managedDecision: AodClockPlacementDecision
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val controllerStates = WeakHashMap<Any, ControllerState>()
    private var lastControllerRef = WeakReference<Any>(null)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val controller = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull() ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        val update = controller.getDeclaredMethod(
            "updateTranslation",
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.deoptimize(update)
        module.hook(update).intercept(PositionHooker)
        HookLogger.i(TAG, "AOD position hook installed")
    }

    private object PositionHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject
            val requestedX = (chain.args.getOrNull(1) as? Number)?.toInt()
            val requestedY = (chain.args.getOrNull(2) as? Number)?.toFloat()
            val animated = chain.args.firstOrNull() as? Boolean ?: false
            val resolution = if (controller != null && requestedX != null && requestedY != null) {
                resolveDecision(controller, requestedX, requestedY)
            } else {
                null
            }
            val decision = resolution?.decision
            val appliedAnimated = shouldAnimateAodPosition(
                requested = animated,
                overridden = decision?.overridden == true,
                placementChanged = decision?.zoneChanged == true
            )
            val result = if (decision != null) {
                chain.proceed(
                    arrayOf<Any>(
                        appliedAnimated,
                        decision.appliedTranslationX,
                        decision.appliedTranslationY
                    )
                )
            } else {
                chain.proceed()
            }
            val translationX = decision?.appliedTranslationX?.toFloat()
                ?: requestedX?.toFloat()
                ?: return result
            val translationY = decision?.appliedTranslationY ?: requestedY ?: return result
            AodSurfaceController.onStockPositionUpdated(
                translationX,
                translationY,
                readFodSafeBottom(controller),
                decision?.clockTop,
                decision?.clockBottom,
                decision?.lyricTopSafe,
                decision?.zone ?: AodSceneZone.STOCK,
                decision?.zoneChanged == true,
                appliedAnimated &&
                    (decision?.overridden == true || decision?.zoneChanged == true)
            )
            return result
        }
    }

    fun restoreStockTranslation() {
        val restore = synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return@synchronized null
            val state = controllerStates[controller] ?: return@synchronized null
            val decision = state.currentManagedDecision ?: return@synchronized null
            val x = state.lastStockTranslationX ?: return@synchronized null
            val y = state.lastStockTranslationY ?: return@synchronized null
            StockRestore(controller, x, y, state.managedStep, decision)
        } ?: return
        mainHandler.post {
            runCatching {
                restore.controller.javaClass.getDeclaredMethod(
                    "updateTranslation",
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                ).apply { isAccessible = true }.invoke(
                    restore.controller,
                    true,
                    restore.stockX,
                    restore.stockY
                )
            }.onFailure {
                synchronized(controllerStates) {
                    controllerStates[restore.controller]?.let { state ->
                        if (shouldClearFailedManagedRestore(
                                state.managedStep,
                                state.currentManagedDecision,
                                restore.managedStep,
                                restore.managedDecision
                            )
                        ) {
                            state.managedStep = -1
                            state.currentManagedDecision = null
                            state.pendingManagedDecision = null
                        }
                    }
                }
                HookLogger.w(TAG, "Stock clock restoration failed", it)
            }
        }
    }

    fun advanceManagedPosition(pattern: String, animated: Boolean = true): Boolean {
        val advance = synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return@synchronized null
            val state = controllerStates[controller] ?: return@synchronized null
            val stockX = state.lastStockTranslationX ?: return@synchronized null
            val stockY = state.lastStockTranslationY ?: return@synchronized null
            val geometry = readClockGeometry(controller) ?: return@synchronized null
            val previousStep = state.managedStep
            val previousDecision = state.currentManagedDecision
            val nextStep = previousStep + 1
            val decision = managedAodClockDecision(pattern, nextStep, stockX, stockY, geometry)
                ?: return@synchronized null
            state.managedStep = nextStep
            state.currentManagedDecision = decision
            state.pendingManagedDecision = decision
            ManagedAdvance(
                controller,
                previousStep,
                previousDecision,
                nextStep,
                decision
            )
        } ?: return false
        return runCatching {
            advance.controller.javaClass.getDeclaredMethod(
                "updateTranslation",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(
                advance.controller,
                animated,
                advance.attemptedDecision.appliedTranslationX,
                advance.attemptedDecision.appliedTranslationY
            )
            true
        }.getOrElse {
            synchronized(controllerStates) {
                controllerStates[advance.controller]?.let { state ->
                    if (shouldRollbackFailedManagedAdvance(
                            state.managedStep,
                            state.currentManagedDecision,
                            advance.attemptedStep,
                            advance.attemptedDecision
                        )
                    ) {
                        state.managedStep = advance.previousStep
                        state.currentManagedDecision = advance.previousDecision
                    }
                    if (state.pendingManagedDecision == advance.attemptedDecision) {
                        state.pendingManagedDecision = null
                    }
                }
            }
            HookLogger.w(TAG, "Managed clock movement failed", it)
            false
        }
    }

    fun restartManagedPattern() {
        synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return
            controllerStates[controller]?.apply {
                managedStep = -1
                currentManagedDecision = null
                pendingManagedDecision = null
            }
        }
    }

    fun hasManagedPosition(): Boolean = synchronized(controllerStates) {
        val controller = lastControllerRef.get() ?: return@synchronized false
        controllerStates[controller]?.currentManagedDecision != null
    }

    fun abandonManagedSession() {
        synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return
            controllerStates[controller]?.apply {
                managedStep = -1
                currentManagedDecision = null
                pendingManagedDecision = null
            }
        }
    }

    private fun resolveDecision(
        controller: Any,
        requestedX: Int,
        requestedY: Float
    ): PositionResolution? {
        val geometry = readClockGeometry(controller) ?: return null
        return synchronized(controllerStates) {
            val state = controllerStates.getOrPut(controller) { ControllerState() }
            state.pendingManagedDecision?.let { pending ->
                state.pendingManagedDecision = null
                return@synchronized PositionResolution(pending)
            }
            state.lastStockTranslationX = requestedX
            state.lastStockTranslationY = requestedY
            lastControllerRef = WeakReference(controller)
            if (AodSurfaceController.isStockWidgetControlActive()) {
                val current = state.currentManagedDecision
                if (current != null) {
                    val refreshed = managedAodClockDecision(
                        AodSurfaceController.managedBurnInPattern(),
                        state.managedStep.coerceAtLeast(0),
                        requestedX,
                        requestedY,
                        geometry
                    )
                    if (refreshed == null) {
                        state.managedStep = -1
                        state.currentManagedDecision = null
                        PositionResolution(
                            stockDecision(requestedX, requestedY, geometry, zoneChanged = true)
                        )
                    } else {
                        val placementChanged = managedAodPlacementChanged(current, refreshed)
                        val resolved = refreshed.copy(zoneChanged = placementChanged)
                        state.currentManagedDecision = resolved
                        PositionResolution(resolved)
                    }
                } else {
                    managedAodClockDecision(
                        AodSurfaceController.managedBurnInPattern(),
                        0,
                        requestedX,
                        requestedY,
                        geometry
                    )?.also {
                        state.managedStep = 0
                        state.currentManagedDecision = it
                    }?.let(::PositionResolution)
                }
            } else {
                val zoneChanged = state.currentManagedDecision != null
                state.managedStep = -1
                state.currentManagedDecision = null
                state.pendingManagedDecision = null
                PositionResolution(stockDecision(requestedX, requestedY, geometry, zoneChanged))
            }
        }
    }

    private fun stockDecision(
        requestedX: Int,
        requestedY: Float,
        geometry: AodClockGeometry,
        zoneChanged: Boolean
    ): AodClockPlacementDecision {
        val top = (requestedY + geometry.viewTop).toInt()
        return AodClockPlacementDecision(
            requestedTranslationX = requestedX,
            requestedTranslationY = requestedY,
            appliedTranslationX = requestedX,
            appliedTranslationY = requestedY,
            clockTop = top,
            clockBottom = top + geometry.viewHeight,
            lyricTopSafe = top.coerceAtLeast(0),
            zone = AodSceneZone.STOCK,
            zoneChanged = zoneChanged,
            overridden = false
        )
    }

    private fun readClockGeometry(controller: Any): AodClockGeometry? = runCatching {
        AodClockGeometry(
            mode = readIntField(controller, "mMode"),
            baseTranslationY = readFloatField(controller, "mTranslationY"),
            translationYStep = readFloatField(controller, "mTranslationYStep"),
            viewTop = readIntField(controller, "mViewTop"),
            viewHeight = readIntField(controller, "mViewHeight"),
            translationXStep = readIntField(controller, "mTranslationX")
        )
    }.getOrNull()

    private fun readIntField(controller: Any, name: String): Int =
        controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(controller)

    private fun readFloatField(controller: Any, name: String): Float =
        controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(controller)

    private fun readFodSafeBottom(controller: Any?): Int? = runCatching {
        controller ?: return null
        val shown = controller.javaClass.getDeclaredField("mIsGxzwIconShow").apply {
            isAccessible = true
        }.getBoolean(controller)
        val y = controller.javaClass.getDeclaredField("mGxzwIconY").apply {
            isAccessible = true
        }.getInt(controller)
        y.takeIf { shown && it > 0 }
    }.getOrNull()

    private const val CONTROLLER_CLASS = "com.miui.aod.AODUpdatePositionController"
    private const val TAG = "AodPositionHook"
}

internal fun shouldRollbackFailedManagedAdvance(
    currentStep: Int,
    currentDecision: AodClockPlacementDecision?,
    attemptedStep: Int,
    attemptedDecision: AodClockPlacementDecision
): Boolean = currentStep == attemptedStep && currentDecision == attemptedDecision

internal fun shouldClearFailedManagedRestore(
    currentStep: Int,
    currentDecision: AodClockPlacementDecision?,
    restoreStep: Int,
    restoreDecision: AodClockPlacementDecision
): Boolean = currentStep == restoreStep && currentDecision == restoreDecision

internal fun shouldAnimateAodPosition(
    requested: Boolean,
    overridden: Boolean,
    placementChanged: Boolean
): Boolean = requested && (!overridden || placementChanged)
