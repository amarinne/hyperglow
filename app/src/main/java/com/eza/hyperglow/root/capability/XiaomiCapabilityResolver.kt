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

internal enum class XiaomiSymbolProbe {
    AOD_SURFACE_LIFECYCLE,
    AOD_HOST_CONTAINER,
    AOD_POSITION_UPDATE,
    AOD_POSITION_TARGET,
    AOD_LIFETIME_POLICY,
    AOD_WAKE_SEAM,
    LOCKSCREEN_SECTION_LIFECYCLE,
    LOCKSCREEN_CONTROLLER,
    LOCKSCREEN_HOST_CONTAINER,
    LOCKSCREEN_CLOCK_GEOMETRY,
    LINKAGE_DIRECTION,
    LINKAGE_GEOMETRY,
    RAISE_TO_AOD,
    LOCKSCREEN_EDITOR_GESTURE,
    FULL_AOD,
    VIDEO_DEPTH
}

internal enum class XiaomiProfileState(val wireValue: String) {
    VERIFIED_PROFILE("verified_profile"),
    VERIFIED_PROFILE_MISSING_SYMBOLS("verified_profile_missing_symbols"),
    UNSUPPORTED_PROFILE("unsupported_profile"),
    EXPERIMENTAL_ELIGIBLE("experimental_eligible"),
    EXPERIMENTAL_ACTIVE("experimental_active");

    companion object {
        fun fromWireValue(value: String): XiaomiProfileState? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

internal data class XiaomiSymbolSnapshot(
    val aodSurface: Boolean = false,
    val aodHostContainer: Boolean = false,
    val aodPositionUpdates: Boolean = false,
    val aodPositionTarget: Boolean = false,
    val aodLifetimeGuard: Boolean = false,
    val aodWakeBroker: Boolean = false,
    val lockscreenHost: Boolean = false,
    val lockscreenController: Boolean = false,
    val lockscreenHostContainer: Boolean = false,
    val lockscreenGeometry: Boolean = false,
    val linkageDirection: Boolean = false,
    val linkageGeometry: Boolean = false,
    val raiseToAod: Boolean = false,
    val lockscreenEditorGesture: Boolean = false,
    val fullAod: Boolean = false,
    val videoDepth: Boolean = false
) {
    fun rawProbes(): Map<XiaomiSymbolProbe, Boolean> = linkedMapOf(
        XiaomiSymbolProbe.AOD_SURFACE_LIFECYCLE to aodSurface,
        XiaomiSymbolProbe.AOD_HOST_CONTAINER to aodHostContainer,
        XiaomiSymbolProbe.AOD_POSITION_UPDATE to aodPositionUpdates,
        XiaomiSymbolProbe.AOD_POSITION_TARGET to aodPositionTarget,
        XiaomiSymbolProbe.AOD_LIFETIME_POLICY to aodLifetimeGuard,
        XiaomiSymbolProbe.AOD_WAKE_SEAM to aodWakeBroker,
        XiaomiSymbolProbe.LOCKSCREEN_SECTION_LIFECYCLE to lockscreenHost,
        XiaomiSymbolProbe.LOCKSCREEN_CONTROLLER to lockscreenController,
        XiaomiSymbolProbe.LOCKSCREEN_HOST_CONTAINER to lockscreenHostContainer,
        XiaomiSymbolProbe.LOCKSCREEN_CLOCK_GEOMETRY to lockscreenGeometry,
        XiaomiSymbolProbe.LINKAGE_DIRECTION to linkageDirection,
        XiaomiSymbolProbe.LINKAGE_GEOMETRY to linkageGeometry,
        XiaomiSymbolProbe.RAISE_TO_AOD to raiseToAod,
        XiaomiSymbolProbe.LOCKSCREEN_EDITOR_GESTURE to lockscreenEditorGesture,
        XiaomiSymbolProbe.FULL_AOD to fullAod,
        XiaomiSymbolProbe.VIDEO_DEPTH to videoDepth
    )
}

internal fun resolveXiaomiCapabilities(
    symbols: XiaomiSymbolSnapshot,
    verifiedRuntimeProfile: Boolean = true,
    experimentalMode: Boolean = false
): Set<XiaomiCapability> {
    // 实验模式:用户在 app 端开启后,即便 verifiedRuntimeProfile=false 也按符号探测结果
    // 放开 capability。hook 端 surface hook 本就基于符号安装,此处 capability 检查只是
    // 双保险;符号在即放行,让 AOD surface/位置更新/保活等链路在未验证版本上也能跑。
    val effective = verifiedRuntimeProfile || experimentalMode
    return EnumSet.noneOf(XiaomiCapability::class.java).apply {
        val aodSurfaceEligible = symbols.aodSurface && symbols.aodHostContainer
        val lockscreenHostEligible = symbols.lockscreenHost && symbols.lockscreenController &&
            symbols.lockscreenHostContainer
        val lockscreenGeometryEligible = lockscreenHostEligible && symbols.lockscreenGeometry
        if (effective && aodSurfaceEligible) add(XiaomiCapability.AOD_SURFACE)
        if (effective && aodSurfaceEligible && symbols.aodPositionUpdates) {
            add(XiaomiCapability.AOD_POSITION_UPDATES)
        }
        if (effective && aodSurfaceEligible && symbols.aodLifetimeGuard) {
            add(XiaomiCapability.AOD_LIFETIME_GUARD)
        }
        if (effective && symbols.aodWakeBroker) {
            add(XiaomiCapability.AOD_WAKE_BROKER)
        }
        if (effective && lockscreenHostEligible) add(XiaomiCapability.LOCKSCREEN_HOST)
        if (effective && lockscreenGeometryEligible) {
            add(XiaomiCapability.LOCKSCREEN_GEOMETRY)
        }
        if (effective && lockscreenHostEligible && symbols.linkageDirection) {
            add(XiaomiCapability.LINKAGE_DIRECTION)
        }
        if (effective && lockscreenGeometryEligible &&
            symbols.linkageDirection && symbols.linkageGeometry
        ) {
            add(XiaomiCapability.LINKAGE_GEOMETRY)
        }
        if (effective && symbols.raiseToAod) add(XiaomiCapability.RAISE_TO_AOD)
        if (effective && symbols.lockscreenEditorGesture) {
            add(XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE)
        }
        if (effective && aodSurfaceEligible && symbols.fullAod) {
            add(XiaomiCapability.FULL_AOD)
        }
        if (effective && lockscreenHostEligible && symbols.videoDepth) {
            add(XiaomiCapability.VIDEO_DEPTH)
        }
    }
}

internal fun resolveXiaomiProfileState(
    symbols: XiaomiSymbolSnapshot,
    verifiedRuntimeProfile: Boolean,
    capabilities: Set<XiaomiCapability>,
    experimentalModeActive: Boolean = false
): XiaomiProfileState {
    if (experimentalModeActive && !verifiedRuntimeProfile) {
        return XiaomiProfileState.EXPERIMENTAL_ACTIVE
    }
    if (verifiedRuntimeProfile) {
        return if (VERIFIED_BASELINE_CAPABILITIES.all(capabilities::contains)) {
            XiaomiProfileState.VERIFIED_PROFILE
        } else {
            XiaomiProfileState.VERIFIED_PROFILE_MISSING_SYMBOLS
        }
    }
    val experimentalSurfaceEligible = symbols.aodSurface && symbols.aodHostContainer ||
        symbols.lockscreenHost && symbols.lockscreenController &&
        symbols.lockscreenHostContainer && symbols.lockscreenGeometry
    return if (experimentalSurfaceEligible) {
        XiaomiProfileState.EXPERIMENTAL_ELIGIBLE
    } else {
        XiaomiProfileState.UNSUPPORTED_PROFILE
    }
}

private val VERIFIED_BASELINE_CAPABILITIES = setOf(
    XiaomiCapability.AOD_SURFACE,
    XiaomiCapability.AOD_POSITION_UPDATES,
    XiaomiCapability.AOD_LIFETIME_GUARD,
    XiaomiCapability.AOD_WAKE_BROKER,
    XiaomiCapability.LOCKSCREEN_HOST,
    XiaomiCapability.LOCKSCREEN_GEOMETRY,
    XiaomiCapability.LINKAGE_DIRECTION,
    XiaomiCapability.LINKAGE_GEOMETRY,
    XiaomiCapability.RAISE_TO_AOD,
    XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE
)

internal data class XiaomiCapabilityReport(
    val protocolVersion: Int = 2,
    val reportedAtUtcMillis: Long = 0L,
    val systemUiVersion: String = "unknown",
    val aodVersion: String = "unknown",
    val symbols: XiaomiSymbolSnapshot = XiaomiSymbolSnapshot(),
    val verifiedRuntimeProfile: Boolean = false,
    val capabilities: Set<XiaomiCapability> = emptySet(),
    val profileState: XiaomiProfileState = XiaomiProfileState.UNSUPPORTED_PROFILE,
    val experimentalModeActive: Boolean = false,
    val rawProbes: Map<XiaomiSymbolProbe, Boolean> = symbols.rawProbes()
) {
    fun summary(): String = buildString {
        append("systemui=").append(systemUiVersion)
        append(" aod=").append(aodVersion)
        append(" verified=").append(if (verifiedRuntimeProfile) 1 else 0)
        append(" state=").append(profileState.wireValue)
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
    /**
     * App 端通过 WirePayload.experimentalMode 推送过来的实验模式开关。开启后,即便
     * verifiedRuntimeProfile=false,[snapshot] 也会按符号探测结果放开 capability,
     * 让 hook 端 surface/位置更新/保活等链路在 EXPERIMENTAL_ELIGIBLE profile 上能跑。
     */
    @Volatile
    private var experimentalMode: Boolean = false

    @Synchronized
    fun setExperimentalMode(enabled: Boolean) {
        if (experimentalMode == enabled) return
        experimentalMode = enabled
        HookLogger.i(TAG, "experimental_mode set to $enabled")
        logIfChanged()
    }

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
            lockscreenGeometry = hasField(
                classLoader,
                KEYGUARD_PANEL_CONTROLLER,
                "keyguardClockInjector",
                KEYGUARD_CLOCK_INJECTOR
            ) && (
                hasNoArgMethod(classLoader, KEYGUARD_CLOCK_INJECTOR, "getClockBottom") ||
                    hasNoArgMethod(classLoader, KEYGUARD_CLOCK_CONTAINER, "getClockBottom")
                ),
            lockscreenController = hasField(
                classLoader,
                KEYGUARD_PANEL_SECTION,
                "keyguardViewController",
                KEYGUARD_PANEL_CONTROLLER
            ) && hasClass(classLoader, KEYGUARD_PANEL_CONTROLLER),
            lockscreenHostContainer = hasField(
                classLoader,
                KEYGUARD_PANEL_CONTROLLER,
                "keyguardTranslationInfo",
                "android.view.ViewGroup"
            ),
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
            ) && hasNoArgMethod(classLoader, KEYGUARD_EDITOR_HELPER, "tryStartEditActivity") &&
                hasNoArgMethod(
                    classLoader,
                    LOCKSCREEN_MAGAZINE_CONTROLLER,
                    "handleSingleClickEvent"
                ),
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
            aodHostContainer = hasField(
                classLoader,
                AOD_VIEW,
                "mTableModeContainer",
                "android.view.View"
            ),
            aodPositionUpdates = hasMethod(
                classLoader,
                AOD_POSITION_CONTROLLER,
                "updateTranslation",
                "boolean",
                "int",
                "float"
            ) && hasNoArgMethod(classLoader, AOD_DOZE_HOST, "updatePosition"),
            aodPositionTarget = hasField(
                classLoader,
                AOD_POSITION_CONTROLLER,
                "mTargetView",
                "android.view.View"
            ),
            aodLifetimeGuard = hasNoArgMethod(classLoader, AOD_LIFETIME_CONTROLLER, "smartHide") &&
                hasNoArgMethod(classLoader, AOD_LIFETIME_CONTROLLER, "hideDoze"),
            aodWakeBroker = hasField(
                classLoader,
                AOD_DOZE_TRIGGERS,
                "mHost",
                AOD_DOZE_HOST
            ) && hasField(
                classLoader,
                AOD_DOZE_TRIGGERS,
                "mContext",
                "android.content.Context"
            ) && hasMethod(
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
            aodHostContainer = aodSymbols.aodHostContainer,
            aodPositionUpdates = aodSymbols.aodPositionUpdates,
            aodPositionTarget = aodSymbols.aodPositionTarget,
            aodLifetimeGuard = aodSymbols.aodLifetimeGuard,
            aodWakeBroker = aodSymbols.aodWakeBroker,
            lockscreenHost = defaultSymbols.lockscreenHost,
            lockscreenController = defaultSymbols.lockscreenController,
            lockscreenHostContainer = defaultSymbols.lockscreenHostContainer,
            lockscreenGeometry = defaultSymbols.lockscreenGeometry,
            linkageDirection = defaultSymbols.linkageDirection,
            linkageGeometry = defaultSymbols.linkageGeometry,
            raiseToAod = defaultSymbols.raiseToAod,
            lockscreenEditorGesture = defaultSymbols.lockscreenEditorGesture,
            fullAod = defaultSymbols.fullAod && aodSymbols.fullAod,
            videoDepth = defaultSymbols.videoDepth
        )
        val verifiedRuntimeProfile = isVerifiedRuntimeProfile(systemUiVersion, aodVersion)
        val experimental = experimentalMode
        val capabilities = resolveXiaomiCapabilities(
            symbols = symbols,
            verifiedRuntimeProfile = verifiedRuntimeProfile,
            experimentalMode = experimental
        )
        return XiaomiCapabilityReport(
            protocolVersion = 2,
            reportedAtUtcMillis = System.currentTimeMillis(),
            systemUiVersion = systemUiVersion,
            aodVersion = aodVersion,
            symbols = symbols,
            verifiedRuntimeProfile = verifiedRuntimeProfile,
            capabilities = capabilities,
            profileState = resolveXiaomiProfileState(
                symbols = symbols,
                verifiedRuntimeProfile = verifiedRuntimeProfile,
                capabilities = capabilities,
                experimentalModeActive = experimental && !verifiedRuntimeProfile
            ),
            experimentalModeActive = experimental && !verifiedRuntimeProfile,
            rawProbes = symbols.rawProbes()
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

    private fun hasField(
        classLoader: ClassLoader,
        className: String,
        fieldName: String,
        expectedTypeName: String? = null
    ): Boolean = runCatching {
        val field = classLoader.loadClass(className).getDeclaredField(fieldName)
        val expectedType = expectedTypeName?.let(classLoader::loadClass) ?: return@runCatching true
        expectedType.isAssignableFrom(field.type)
    }.getOrDefault(false)

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
    private const val LOCKSCREEN_MAGAZINE_CONTROLLER =
        "com.android.keyguard.magazine.LockScreenMagazineController"
    private const val POWER_MANAGER = "android.os.PowerManager"
    private const val FULL_AOD_MANAGER = "com.miui.interfaces.keyguard.IMiuiFullAodManager"
    private const val VIDEO_DEPTH_SURFACE_HOLDER = "com.miui.keyguard.VideoDepthSurfaceHolder"
    private const val VERIFIED_SYSTEM_UI_VERSION_CODE = 202501210L
    // 已验证的 AOD versionCode 集合。22327001 是原始验证版本;22313001 经符号探测确认
    // 结构一致(所有 16 个 probe 全部命中),纳入白名单让 verified profile 解锁。
    private val VERIFIED_AOD_VERSION_CODES = setOf(22327001L, 22313001L)

    internal fun isVerifiedRuntimeProfile(systemUiVersion: String, aodVersion: String): Boolean =
        systemUiVersion.endsWith("($VERIFIED_SYSTEM_UI_VERSION_CODE)") &&
            VERIFIED_AOD_VERSION_CODES.any { code -> aodVersion.endsWith("($code)") }
}
