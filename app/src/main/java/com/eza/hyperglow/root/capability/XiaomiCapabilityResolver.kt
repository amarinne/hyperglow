package com.eza.hyperglow.root.capability

import android.content.Context
import android.os.Build
import com.eza.hyperglow.root.HookLogger
import java.util.EnumSet

internal enum class XiaomiCapability {
    AOD_SURFACE,
    AOD_POSITION_UPDATES,
    AOD_LIFETIME_GUARD,
    AOD_WAKE_BROKER,
    LOCKSCREEN_HOST,
    LOCKSCREEN_GEOMETRY,
    LINKAGE_DIRECTION,
    LINKAGE_GEOMETRY,
    RAISE_TO_AOD,
    LOCKSCREEN_EDITOR_GESTURE,
    FULL_AOD,
    VIDEO_DEPTH
}

internal data class XiaomiSymbolSnapshot(
    val aodSurface: Boolean = false,
    val aodPositionUpdates: Boolean = false,
    val aodLifetimeGuard: Boolean = false,
    val aodWakeBroker: Boolean = false,
    val lockscreenHost: Boolean = false,
    val lockscreenGeometry: Boolean = false,
    val linkageDirection: Boolean = false,
    val linkageGeometry: Boolean = false,
    val raiseToAod: Boolean = false,
    val lockscreenEditorGesture: Boolean = false,
    val fullAod: Boolean = false,
    val videoDepth: Boolean = false
)

internal fun resolveXiaomiCapabilities(
    symbols: XiaomiSymbolSnapshot,
    verifiedRuntimeProfile: Boolean = true
): Set<XiaomiCapability> =
    EnumSet.noneOf(XiaomiCapability::class.java).apply {
        if (verifiedRuntimeProfile && symbols.aodSurface) add(XiaomiCapability.AOD_SURFACE)
        if (verifiedRuntimeProfile && symbols.aodSurface && symbols.aodPositionUpdates) {
            add(XiaomiCapability.AOD_POSITION_UPDATES)
        }
        if (verifiedRuntimeProfile && symbols.aodSurface && symbols.aodLifetimeGuard) {
            add(XiaomiCapability.AOD_LIFETIME_GUARD)
        }
        if (verifiedRuntimeProfile && symbols.aodWakeBroker) {
            add(XiaomiCapability.AOD_WAKE_BROKER)
        }
        if (verifiedRuntimeProfile && symbols.lockscreenHost) add(XiaomiCapability.LOCKSCREEN_HOST)
        if (verifiedRuntimeProfile && symbols.lockscreenHost && symbols.lockscreenGeometry) {
            add(XiaomiCapability.LOCKSCREEN_GEOMETRY)
        }
        if (verifiedRuntimeProfile && symbols.lockscreenHost && symbols.linkageDirection) {
            add(XiaomiCapability.LINKAGE_DIRECTION)
        }
        if (verifiedRuntimeProfile && symbols.lockscreenHost && symbols.lockscreenGeometry &&
            symbols.linkageDirection && symbols.linkageGeometry
        ) {
            add(XiaomiCapability.LINKAGE_GEOMETRY)
        }
        if (verifiedRuntimeProfile && symbols.raiseToAod) add(XiaomiCapability.RAISE_TO_AOD)
        if (verifiedRuntimeProfile && symbols.lockscreenEditorGesture) {
            add(XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE)
        }
        if (verifiedRuntimeProfile && symbols.aodSurface && symbols.fullAod) {
            add(XiaomiCapability.FULL_AOD)
        }
        if (verifiedRuntimeProfile && symbols.lockscreenHost && symbols.videoDepth) {
            add(XiaomiCapability.VIDEO_DEPTH)
        }
    }

internal data class XiaomiCapabilityReport(
    val systemUiVersion: String = "unknown",
    val aodVersion: String = "unknown",
    val symbols: XiaomiSymbolSnapshot = XiaomiSymbolSnapshot(),
    val verifiedRuntimeProfile: Boolean = false,
    val capabilities: Set<XiaomiCapability> = emptySet()
) {
    fun summary(): String = buildString {
        append("systemui=").append(systemUiVersion)
        append(" aod=").append(aodVersion)
        append(" verified=").append(if (verifiedRuntimeProfile) 1 else 0)
        append(" capabilities=")
        append(
            XiaomiCapability.entries.joinToString(",") { capability ->
                "${capability.name}:${if (capability in capabilities) 1 else 0}"
            }
        )
    }
}

internal object XiaomiCapabilityResolver {
    private const val TAG = "XiaomiCapabilities"
    private var defaultSymbols = XiaomiSymbolSnapshot()
    private var aodSymbols = XiaomiSymbolSnapshot()
    private var systemUiVersion = "unknown"
    private var aodVersion = "unknown"
    private var lastSummary = ""

    @Synchronized
    fun observeDefaultLoader(classLoader: ClassLoader) {
        defaultSymbols = XiaomiSymbolSnapshot(
            lockscreenHost = hasMethod(
                classLoader,
                KEYGUARD_PANEL_SECTION,
                "bindData",
                "androidx.constraintlayout.widget.ConstraintLayout"
            ) && hasMethod(
                classLoader,
                KEYGUARD_PANEL_SECTION,
                "removeViews",
                "androidx.constraintlayout.widget.ConstraintLayout"
            ),
            lockscreenGeometry = hasNoArgMethod(
                classLoader,
                KEYGUARD_CLOCK_INJECTOR,
                "getClockBottom"
            ) || hasNoArgMethod(classLoader, KEYGUARD_CLOCK_CONTAINER, "getClockBottom"),
            linkageDirection = hasMethod(
                classLoader,
                KEYGUARD_PANEL_CONTROLLER,
                "linkageViewAnim\$default",
                KEYGUARD_PANEL_CONTROLLER,
                "boolean",
                "java.lang.String",
                "int"
            ) || hasMethod(
                classLoader,
                ANIMATION_HELPER,
                "doAnimationToAod",
                "boolean",
                "boolean",
                "boolean"
            ),
            linkageGeometry = hasNoArgMethod(
                classLoader,
                KEYGUARD_CLOCK_CONTAINER,
                "getAodClockTranslation"
            ),
            raiseToAod = hasClass(classLoader, KEYGUARD_SENSOR_INJECTOR) && hasMethod(
                classLoader,
                POWER_MANAGER,
                "wakeUp",
                "long",
                "java.lang.String"
            ),
            lockscreenEditorGesture = hasMethod(
                classLoader,
                KEYGUARD_EDITOR_HELPER,
                "onTouchEvent",
                "android.view.MotionEvent"
            ) && hasNoArgMethod(classLoader, KEYGUARD_EDITOR_HELPER, "tryStartEditActivity"),
            fullAod = hasClass(classLoader, FULL_AOD_MANAGER),
            videoDepth = hasClass(classLoader, VIDEO_DEPTH_SURFACE_HOLDER)
        )
        logIfChanged()
    }

    @Synchronized
    fun observeAodLoader(classLoader: ClassLoader) {
        if (!hasClass(classLoader, AOD_VIEW)) return
        aodSymbols = XiaomiSymbolSnapshot(
            aodSurface = hasNoArgMethod(classLoader, AOD_VIEW, "onAttachedToWindow") &&
                hasNoArgMethod(classLoader, AOD_VIEW, "onDetachedFromWindow"),
            aodPositionUpdates = hasMethod(
                classLoader,
                AOD_POSITION_CONTROLLER,
                "updateTranslation",
                "boolean",
                "int",
                "float"
            ) && hasNoArgMethod(classLoader, AOD_DOZE_HOST, "updatePosition"),
            aodLifetimeGuard = hasNoArgMethod(classLoader, AOD_LIFETIME_CONTROLLER, "smartHide") &&
                hasNoArgMethod(classLoader, AOD_LIFETIME_CONTROLLER, "hideDoze"),
            aodWakeBroker = hasField(classLoader, AOD_DOZE_TRIGGERS, "mHost") &&
                hasField(classLoader, AOD_DOZE_TRIGGERS, "mContext") && hasMethod(
                classLoader,
                AOD_DOZE_HOST,
                "fireAodState",
                "boolean",
                "java.lang.String"
            ),
            fullAod = hasNoArgMethod(classLoader, AOD_SETTINGS, "needFullAod")
        )
        logIfChanged()
    }

    @Synchronized
    fun observeContext(context: Context) {
        systemUiVersion = packageVersion(context, SYSTEM_UI_PACKAGE)
        aodVersion = packageVersion(context, AOD_PACKAGE)
        logIfChanged()
    }

    @Synchronized
    fun snapshot(): XiaomiCapabilityReport {
        val symbols = XiaomiSymbolSnapshot(
            aodSurface = aodSymbols.aodSurface,
            aodPositionUpdates = aodSymbols.aodPositionUpdates,
            aodLifetimeGuard = aodSymbols.aodLifetimeGuard,
            aodWakeBroker = aodSymbols.aodWakeBroker,
            lockscreenHost = defaultSymbols.lockscreenHost,
            lockscreenGeometry = defaultSymbols.lockscreenGeometry,
            linkageDirection = defaultSymbols.linkageDirection,
            linkageGeometry = defaultSymbols.linkageGeometry,
            raiseToAod = defaultSymbols.raiseToAod,
            lockscreenEditorGesture = defaultSymbols.lockscreenEditorGesture,
            fullAod = defaultSymbols.fullAod && aodSymbols.fullAod,
            videoDepth = defaultSymbols.videoDepth
        )
        val verifiedRuntimeProfile = isVerifiedRuntimeProfile(systemUiVersion, aodVersion)
        return XiaomiCapabilityReport(
            systemUiVersion,
            aodVersion,
            symbols,
            verifiedRuntimeProfile,
            resolveXiaomiCapabilities(symbols, verifiedRuntimeProfile)
        )
    }

    @Synchronized
    fun hasCapability(capability: XiaomiCapability): Boolean = capability in snapshot().capabilities

    private fun logIfChanged() {
        val summary = snapshot().summary()
        if (summary == lastSummary) return
        lastSummary = summary
        HookLogger.i(TAG, summary)
    }

    private fun packageVersion(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val versionName = info.versionName.orEmpty().ifBlank { "unknown" }
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "$versionName($versionCode)"
    }.getOrDefault("missing")

    private fun hasClass(classLoader: ClassLoader, className: String): Boolean =
        runCatching { classLoader.loadClass(className) }.isSuccess

    private fun hasNoArgMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String
    ): Boolean = hasMethod(classLoader, className, methodName)

    private fun hasField(classLoader: ClassLoader, className: String, fieldName: String): Boolean =
        runCatching { classLoader.loadClass(className).getDeclaredField(fieldName) }.isSuccess

    private fun hasMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        vararg parameterTypeNames: String
    ): Boolean = runCatching {
        val owner = classLoader.loadClass(className)
        val parameterTypes = parameterTypeNames.map { typeName ->
            primitiveClass(typeName) ?: classLoader.loadClass(typeName)
        }.toTypedArray()
        owner.getDeclaredMethod(methodName, *parameterTypes)
    }.isSuccess

    private fun primitiveClass(name: String): Class<*>? = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType
        "int" -> Int::class.javaPrimitiveType
        "float" -> Float::class.javaPrimitiveType
        "long" -> Long::class.javaPrimitiveType
        else -> null
    }

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val AOD_PACKAGE = "com.miui.aod"
    private const val AOD_VIEW = "com.miui.aod.AODView"
    private const val AOD_POSITION_CONTROLLER = "com.miui.aod.AODUpdatePositionController"
    private const val AOD_LIFETIME_CONTROLLER = "com.miui.aod.doze.MiuiShowStyleController"
    private const val AOD_DOZE_TRIGGERS = "com.miui.aod.doze.DozeTriggers"
    private const val AOD_DOZE_HOST = "com.miui.aod.DozeHost"
    private const val AOD_SETTINGS = "com.miui.aod.widget.AODSettings"
    private const val KEYGUARD_PANEL_SECTION =
        "com.android.keyguard.blueprint.KeyguardPanelViewSection"
    private const val KEYGUARD_PANEL_CONTROLLER =
        "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val KEYGUARD_CLOCK_INJECTOR =
        "com.android.keyguard.injector.KeyguardClockInjector"
    private const val KEYGUARD_CLOCK_CONTAINER =
        "com.android.keyguard.clock.KeyguardClockContainer"
    private const val ANIMATION_HELPER = "com.android.keyguard.clock.animation.AnimationHelper"
    private const val KEYGUARD_SENSOR_INJECTOR =
        "com.android.keyguard.injector.KeyguardSensorInjector"
    private const val KEYGUARD_EDITOR_HELPER =
        "com.android.keyguard.editor.KeyguardEditorHelper"
    private const val POWER_MANAGER = "android.os.PowerManager"
    private const val FULL_AOD_MANAGER = "com.miui.interfaces.keyguard.IMiuiFullAodManager"
    private const val VIDEO_DEPTH_SURFACE_HOLDER = "com.miui.keyguard.VideoDepthSurfaceHolder"
    private const val VERIFIED_SYSTEM_UI_VERSION_CODE = 202501210L
    private const val VERIFIED_AOD_VERSION_CODE = 22327001L

    internal fun isVerifiedRuntimeProfile(systemUiVersion: String, aodVersion: String): Boolean =
        systemUiVersion.endsWith("($VERIFIED_SYSTEM_UI_VERSION_CODE)") &&
            aodVersion.endsWith("($VERIFIED_AOD_VERSION_CODE)")
}
