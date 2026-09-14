package com.eza.hyperglow.root.symbols

import android.content.Context
import com.eza.hyperglow.root.HookLogger
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * Where a resolved symbol came from.
 */
enum class SymbolSource { BUNDLED, DEXKIT }

/**
 * Sourcing strategy per hook install:
 * - [SymbolPolicy.BUNDLED]: reflection with the baked Xiaomi/Keyguard names.
 * - [SymbolPolicy.DEXKIT_ONLY]: answer every request through DexKit (experiment arm).
 * - [SymbolPolicy.DEXKIT_FALLBACK]: reflection first, DexKit only on a miss — the production
 *   default. A bridge-unavailable DexKit lookup stays retryable, so the AOD loader's arrival can
 *   still satisfy earlier misses.
 */
enum class SymbolPolicy { BUNDLED, DEXKIT_ONLY, DEXKIT_FALLBACK }

/**
 * An exact owner/member contract shared by reflection and DexKit. Renamed symbols require
 * separately verified fingerprints; this resolver does not guess neighboring hook targets.
 */
data class SymbolRequest(
    val ownerClassName: String?,
    val memberName: String,
    val parameterTypeNames: List<String> = emptyList(),
    val expectedFieldType: String? = null,
    val dexKitSearchPackages: List<String> = emptyList()
) {
    companion object {
        fun method(owner: String, name: String, vararg parameterTypes: String) =
            SymbolRequest(owner, name, parameterTypes.toList())

        fun field(owner: String, name: String, expectedType: String? = null) =
            SymbolRequest(owner, name, expectedFieldType = expectedType)
    }
}

data class ResolvedSymbol(
    val source: SymbolSource,
    val method: Method? = null,
    val field: java.lang.reflect.Field? = null,
    val resolveNanos: Long = 0
)

/**
 * Hooking a private member does not require accessibility on the module side, but invoking the
 * captured invoker does; every resolved member is marked accessible once, here.
 */
private fun Method.accessible(): Method = apply { isAccessible = true }

private fun java.lang.reflect.Field.accessible() = apply { isAccessible = true }

data class SymbolStats(
    val policy: SymbolPolicy,
    val dexKitBridgeMillis: Long,
    val dexKitQueries: Long,
    val dexKitMillis: Long,
    val bundledHits: Long,
    val bundledMisses: Long,
    val bundledMillis: Long
)

/**
 * Per-feature provenance ledger: which feature asked, what it needed, and which arm resolved it.
 * Resolution counts are *successful* resolutions; attempts are tracked separately by the
 * resolver, so a failed try cannot inflate the arm's effectiveness.
 */
object SymbolSourceLog {
    private data class Entry(
        val featureId: String,
        val descriptor: String,
        val hit: Boolean,
        val source: SymbolSource?
    )

    private val entries = ArrayList<Entry>()
    private var bridgeNanosTotal = 0L

    fun record(featureId: String, descriptor: String, hit: Boolean, source: SymbolSource?) {
        synchronized(this) { entries.add(Entry(featureId, descriptor, hit, source)) }
    }

    /** Accrues bridge-open time; individual creates are summed, never overwritten. */
    fun bridgeNanos(value: Long) {
        synchronized(this) { bridgeNanosTotal += value }
    }

    fun bridgeTotal(): Long = synchronized(this) { bridgeNanosTotal / 1_000_000L }

    fun reset() {
        synchronized(this) { entries.clear(); bridgeNanosTotal = 0 }
    }

    fun summary(): String = synchronized(this) {
        val dex = entries.count { it.hit && it.source == SymbolSource.DEXKIT }
        val bundled = entries.count { it.hit && it.source == SymbolSource.BUNDLED }
        val misses = entries.count { !it.hit }
        buildString {
            append("bundled=").append(bundled)
            append(" dexkit=").append(dex)
            append(" miss=").append(misses)
            append(" bridgeMs=").append(bridgeNanosTotal / 1_000_000L)
            misses.takeIf { it > 0 }?.let { append(" misses="); append(
                entries.filter { !it.hit }.joinToString(",") {
                    "${it.featureId}:${it.descriptor}"
                }
            ) }
        }
    }
}

/**
 * Central symbol gate every hook install and capability probe routes through. Sourcing policy is
 * read once per application so the owner can compare arms on the live device without rebuilding:
 *
 *   su -c setprop debug.hyperglow.symbols dexkit-only
 */
object SymbolResolver {
    private const val TAG = "SymbolResolver"
    private const val POLICY_PROPERTY = "debug.hyperglow.symbols"
    private const val DEXKIT_ONLY_VALUE = "dexkit-only"
    private const val DEXKIT_FALLBACK_VALUE = "dexkit-fallback"
    private const val BUNDLED_VALUE = "bundled"
    private const val NANOS_PER_MS = 1_000_000L

    @Volatile
    private var module: XposedModule? = null
    @Volatile
    private var hostContext: Context? = null
    @Volatile
    private var policy: SymbolPolicy = SymbolPolicy.DEXKIT_FALLBACK

    private val dexKitQueries = AtomicCounter()
    private val dexKitNanos = AtomicCounter()
    private val bundledHits = AtomicCounter()
    private val bundledMisses = AtomicCounter()
    private val bundledNanos = AtomicCounter()

    private val methodCache = SymbolCache<Method>()
    private val fieldCache = SymbolCache<java.lang.reflect.Field>()
    private val classCache = SymbolCache<Class<*>>()

    fun install(module: XposedModule): SymbolPolicy {
        this.module = module
        this.policy = readPolicy()
        dexKitQueries.reset()
        dexKitNanos.reset()
        bundledHits.reset()
        bundledMisses.reset()
        bundledNanos.reset()
        SymbolSourceLog.reset()
        HookLogger.i(TAG, "policy=${policy.name}")
        return policy
    }

    /**
     * Arrives once the host application actually exists. Without it the extraction fallback has
     * nowhere writable to put `libdexkit.so`, so this keeps the runtime retryable.
     */
    fun observeContext(context: Context) {
        hostContext = context
    }

    /** Hot-reload boundary: retirement re-derives every loader's entries. */
    fun clearCaches() {
        methodCache.clear()
        fieldCache.clear()
        classCache.clear()
        DexKitRuntime.clearBridges()
    }

    fun resolveClass(loader: ClassLoader, featureId: String, owner: String): Class<*>? {
        val request = SymbolRequest(owner, "<class>")
        classCache.read(loader, request)?.let { return it.value }
        val resolution: Pair<Class<*>?, SymbolSource?> = when {
            frameworkOwned(owner) || policy == SymbolPolicy.BUNDLED ->
                bundledClass(loader, request) to SymbolSource.BUNDLED
            policy == SymbolPolicy.DEXKIT_ONLY -> dexKitClass(loader, request) to SymbolSource.DEXKIT
            else -> {
                val bundled = bundledClass(loader, request)
                if (bundled != null) bundled to SymbolSource.BUNDLED
                else dexKitClass(loader, request) to SymbolSource.DEXKIT
            }
        }
        // A null result is only cached when the arm actually ran with its bridge ready; a miss
        // from a not-yet-created bridge stays retryable for the next observation.
        if (resolution.first != null || resolution.second != SymbolSource.DEXKIT ||
            DexKitRuntime.hasPermanentResult(loader)
        ) {
            classCache.write(loader, request, resolution.first)
        }
        SymbolSourceLog.record(featureId, "class:$owner", resolution.first != null, resolution.second)
        return resolution.first
    }

    fun resolveMethod(
        loader: ClassLoader,
        featureId: String,
        request: SymbolRequest
    ): Method? {
        methodCache.read(loader, request)?.let { return it.value }
        val outcome: LookupOutcome = when {
            frameworkOwned(request.ownerClassName) || policy == SymbolPolicy.BUNDLED ->
                bundledMethod(loader, request)
            policy == SymbolPolicy.DEXKIT_ONLY -> dexKitMethod(loader, request)
            else -> bundledMethod(loader, request).takeIf { it.symbol != null }
                ?: dexKitMethod(loader, request)
        }
        if (outcome.cacheable) {
            methodCache.write(loader, request, outcome.symbol?.method)
        }
        SymbolSourceLog.record(
            featureId,
            "method:${request.ownerClassName ?: "*"}#${request.memberName}",
            outcome.symbol != null,
            outcome.symbol?.source
        )
        return outcome.symbol?.method
    }

    fun resolveField(
        loader: ClassLoader,
        featureId: String,
        request: SymbolRequest
    ): java.lang.reflect.Field? {
        fieldCache.read(loader, request)?.let { return it.value }
        val outcome: LookupOutcome = when {
            frameworkOwned(request.ownerClassName) || policy == SymbolPolicy.BUNDLED ->
                bundledField(loader, request)
            policy == SymbolPolicy.DEXKIT_ONLY -> dexKitField(loader, request)
            else -> bundledField(loader, request).takeIf { it.symbol != null }
                ?: dexKitField(loader, request)
        }
        if (outcome.cacheable) {
            fieldCache.write(loader, request, outcome.symbol?.field)
        }
        SymbolSourceLog.record(
            featureId,
            "field:${request.ownerClassName ?: "*"}#${request.memberName}",
            outcome.symbol != null,
            outcome.symbol?.source
        )
        return outcome.symbol?.field
    }

    /**
     * Bundled lookups are permanent for the exact name/type pair. DexKit misses split: a miss
     * with a live bridge is permanent; a miss while native loading waits for context retries on
     * the next observation. Recoverable query failures disable only the requested symbol.
     */
    private sealed class LookupOutcome(val symbol: ResolvedSymbol?, val cacheable: Boolean) {
        class Resolved(value: ResolvedSymbol) : LookupOutcome(value, true)
        object MissPermanent : LookupOutcome(null, true)
        object MissRetryable : LookupOutcome(null, false)
    }

    fun stats(): SymbolStats = SymbolStats(
        policy = policy,
        dexKitBridgeMillis = SymbolSourceLog.bridgeTotal(),
        dexKitQueries = dexKitQueries.value,
        dexKitMillis = dexKitNanos.value / NANOS_PER_MS,
        bundledHits = bundledHits.value,
        bundledMisses = bundledMisses.value,
        bundledMillis = bundledNanos.value / NANOS_PER_MS
    )

    fun statsLine(): String = stats().let {
        "policy=${it.policy.name} dexQ=${it.dexKitQueries} dexMs=${it.dexKitMillis} " +
            "bHit=${it.bundledHits} bMiss=${it.bundledMisses} bMs=${it.bundledMillis} " +
            "bridgeMs=${it.dexKitBridgeMillis}"
    }

    private fun readPolicy(): SymbolPolicy {
        val value = try {
            (Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java)
                .invoke(null, POLICY_PROPERTY) as? String).orEmpty().trim()
        } catch (_: ReflectiveOperationException) {
            ""
        }
        return when (value) {
            DEXKIT_ONLY_VALUE -> SymbolPolicy.DEXKIT_ONLY
            DEXKIT_FALLBACK_VALUE -> SymbolPolicy.DEXKIT_FALLBACK
            BUNDLED_VALUE -> SymbolPolicy.BUNDLED
            "" -> SymbolPolicy.DEXKIT_FALLBACK
            else -> SymbolPolicy.BUNDLED.also {
                HookLogger.w(TAG, "unrecognized policy '$value'; bundled")
            }
        }
    }

    private fun bundledClass(loader: ClassLoader, request: SymbolRequest): Class<*>? {
        val name = request.ownerClassName ?: return null
        val started = System.nanoTime()
        try {
            val result = loader.loadClass(name)
            if (result == null) bundledMisses.increment() else bundledHits.increment()
            return result
        } catch (_: ClassNotFoundException) {
            bundledMisses.increment()
            return null
        } catch (error: Exception) {
            HookLogger.w(TAG, "bundled class lookup failed name=$name", error)
            bundledMisses.increment()
            return null
        } finally {
            bundledNanos.add(System.nanoTime() - started)
        }
    }

    private fun bundledMethod(loader: ClassLoader, request: SymbolRequest): LookupOutcome {
        val owner = request.ownerClassName ?: return LookupOutcome.MissPermanent
        val started = System.nanoTime()
        try {
            val ownerType = loader.loadClass(owner)
            // One wrong ROM type must cost one capability, not the whole bootstrap: a parameter
            // class that fails to load marks the exact request failed rather than throwing.
            val parameterTypes = request.parameterTypeNames.map { typeName ->
                primitiveClass(typeName) ?: loader.loadClass(typeName)
            }.toTypedArray()
            val method = ownerType.getDeclaredMethod(request.memberName, *parameterTypes)
            bundledHits.increment()
            return LookupOutcome.Resolved(
                ResolvedSymbol(
                    source = SymbolSource.BUNDLED,
                    method = method.accessible(),
                    resolveNanos = System.nanoTime() - started
                )
            )
        } catch (_: ReflectiveOperationException) {
            bundledMisses.increment()
            return LookupOutcome.MissPermanent
        } catch (error: Exception) {
            bundledMisses.increment()
            HookLogger.w(TAG, "bundled method lookup failed member=${request.memberName}", error)
            return LookupOutcome.MissPermanent
        } finally {
            bundledNanos.add(System.nanoTime() - started)
        }
    }

    private fun bundledField(loader: ClassLoader, request: SymbolRequest): LookupOutcome {
        val started = System.nanoTime()
        try {
            var cursor: Class<*>? = loader.loadClass(request.ownerClassName.orEmpty())
            while (cursor != null) {
                val field = try {
                    cursor.getDeclaredField(request.memberName)
                } catch (_: NoSuchFieldException) {
                    null
                }
                if (field != null) {
                    val expected = request.expectedFieldType?.let {
                        primitiveClass(it) ?: loader.loadClass(it)
                    }
                    // A declared expected type that cannot load rejects the probe rather than
                    // relaxing the contract: the hook contract needs the type known.
                    val typeMatches = when {
                        expected == null && request.expectedFieldType != null -> false
                        expected == null -> true
                        else -> expected.isAssignableFrom(field.type)
                    }
                    if (typeMatches) {
                        bundledHits.increment()
                        return LookupOutcome.Resolved(
                            ResolvedSymbol(
                                source = SymbolSource.BUNDLED,
                                field = field.accessible(),
                                resolveNanos = System.nanoTime() - started
                            )
                        )
                    }
                }
                cursor = cursor.superclass
            }
            bundledMisses.increment()
            return LookupOutcome.MissPermanent
        } catch (_: ReflectiveOperationException) {
            bundledMisses.increment()
            return LookupOutcome.MissPermanent
        } catch (error: Exception) {
            bundledMisses.increment()
            HookLogger.w(TAG, "bundled field lookup failed member=${request.memberName}", error)
            return LookupOutcome.MissPermanent
        } finally {
            bundledNanos.add(System.nanoTime() - started)
        }
    }

    private fun dexBridges(loader: ClassLoader): List<DexKitBridge> {
        val module = module ?: return emptyList()
        return DexKitRuntime.ensureBridges(module, loader, hostContext)
    }

    private fun dexKitMethod(loader: ClassLoader, request: SymbolRequest): LookupOutcome {
        val started = System.nanoTime()
        dexKitQueries.increment()
        try {
            val bridgeList = dexBridges(loader)
            if (bridgeList.isEmpty()) {
                return if (DexKitRuntime.hasPermanentResult(loader)) LookupOutcome.MissPermanent
                else LookupOutcome.MissRetryable
            }
            val matcher = MethodMatcher.create().apply {
                name(request.memberName)
                request.ownerClassName?.let { declaredClass(it) }
                if (request.parameterTypeNames.isEmpty()) {
                    // Zero-argument requests must pin zero parameters explicitly; a name-only
                    // match would hook whichever overload DexKit happens to find first.
                    paramTypes()
                } else {
                    paramTypes(request.parameterTypeNames)
                }
            }
            val query = FindMethod.create().matcher(matcher).apply {
                if (request.dexKitSearchPackages.isNotEmpty()) {
                    searchPackages(*request.dexKitSearchPackages.toTypedArray())
                }
            }
            var resolved: Method? = null
            for (bridge in bridgeList) {
                val results = bridge.findMethod(query)
                if (results.isEmpty()) continue
                if (results.size != 1) {
                    HookLogger.w(
                        TAG,
                        "dexkit method ambiguity member=${request.memberName} " +
                            "owner=${request.ownerClassName} matches=${results.size} in one bridge"
                    )
                    return LookupOutcome.MissPermanent
                }
                val candidate = results.first().getMethodInstance(loader)
                if (resolved != null && resolved != candidate) {
                    HookLogger.w(
                        TAG,
                        "dexkit method ambiguity member=${request.memberName} " +
                            "owner=${request.ownerClassName} across bridges"
                    )
                    return LookupOutcome.MissPermanent
                }
                resolved = candidate
            }
            if (resolved == null) {
                HookLogger.w(
                    TAG,
                    "dexkit method miss member=${request.memberName} " +
                        "owner=${request.ownerClassName}"
                )
                return LookupOutcome.MissPermanent
            }
            return LookupOutcome.Resolved(
                ResolvedSymbol(
                    source = SymbolSource.DEXKIT,
                    method = resolved.accessible(),
                    resolveNanos = System.nanoTime() - started
                )
            )
        } catch (error: Exception) {
            HookLogger.w(TAG, "dexkit method lookup failed member=${request.memberName}", error)
            return LookupOutcome.MissPermanent
        } finally {
            dexKitNanos.add(System.nanoTime() - started)
        }
    }

    private fun dexKitField(loader: ClassLoader, request: SymbolRequest): LookupOutcome {
        val started = System.nanoTime()
        dexKitQueries.increment()
        try {
            val bridgeList = dexBridges(loader)
            if (bridgeList.isEmpty()) {
                return if (DexKitRuntime.hasPermanentResult(loader)) LookupOutcome.MissPermanent
                else LookupOutcome.MissRetryable
            }
            val matcher = FieldMatcher.create().apply {
                name(request.memberName)
                request.ownerClassName?.let { declaredClass(it) }
                request.expectedFieldType?.let { type(it) }
            }
            val query = FindField.create().matcher(matcher).apply {
                if (request.dexKitSearchPackages.isNotEmpty()) {
                    searchPackages(*request.dexKitSearchPackages.toTypedArray())
                }
            }
            var resolved: java.lang.reflect.Field? = null
            for (bridge in bridgeList) {
                val results = bridge.findField(query)
                if (results.isEmpty()) continue
                if (results.size != 1) {
                    HookLogger.w(
                        TAG,
                        "dexkit field ambiguity member=${request.memberName} " +
                            "owner=${request.ownerClassName} matches=${results.size} in one bridge"
                    )
                    return LookupOutcome.MissPermanent
                }
                val candidate = results.first().getFieldInstance(loader)
                if (resolved != null && resolved != candidate) {
                    HookLogger.w(
                        TAG,
                        "dexkit field ambiguity member=${request.memberName} " +
                            "owner=${request.ownerClassName} across bridges"
                    )
                    return LookupOutcome.MissPermanent
                }
                resolved = candidate
            }
            if (resolved == null) {
                HookLogger.w(
                    TAG,
                    "dexkit field miss member=${request.memberName} " +
                        "owner=${request.ownerClassName}"
                )
                return LookupOutcome.MissPermanent
            }
            return LookupOutcome.Resolved(
                ResolvedSymbol(
                    source = SymbolSource.DEXKIT,
                    field = resolved.accessible(),
                    resolveNanos = System.nanoTime() - started
                )
            )
        } catch (error: Exception) {
            HookLogger.w(TAG, "dexkit field lookup failed member=${request.memberName}", error)
            return LookupOutcome.MissPermanent
        } finally {
            dexKitNanos.add(System.nanoTime() - started)
        }
    }

    private fun dexKitClass(loader: ClassLoader, request: SymbolRequest): Class<*>? {
        val className = request.ownerClassName ?: return null
        val started = System.nanoTime()
        dexKitQueries.increment()
        try {
            for (bridge in dexBridges(loader)) {
                val found = bridge.findClass(
                    FindClass.create().matcher(ClassMatcher.create().className(className))
                )
                if (found.isEmpty()) continue
                if (found.size != 1) {
                    HookLogger.w(TAG, "dexkit class ambiguity owner=$className matches=${found.size}")
                    return null
                }
                return found.first().getInstance(loader)
            }
            return null
        } catch (error: Exception) {
            HookLogger.w(TAG, "dexkit class lookup failed owner=$className", error)
            return null
        } finally {
            dexKitNanos.add(System.nanoTime() - started)
        }
    }

    private fun primitiveClass(name: String): Class<*>? = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType
        "int" -> Int::class.javaPrimitiveType
        "float" -> Float::class.javaPrimitiveType
        "long" -> Long::class.javaPrimitiveType
        "double" -> Double::class.javaPrimitiveType
        "short" -> Short::class.javaPrimitiveType
        "byte" -> Byte::class.javaPrimitiveType
        "char" -> Char::class.javaPrimitiveType
        else -> null
    }

    /**
     * Framework dexes never participate in a host apk bridge, so boot-classpath owners stay
     * reflection-only under every policy; they are stable across ROM updates, so DexKit adds
     * nothing there and probing them through a host-apk bridge would only produce misses.
     */
    private fun frameworkOwned(ownerClassName: String?): Boolean {
        val owner = ownerClassName ?: return false
        return owner.startsWith("android.") ||
            owner.startsWith("java.") ||
            owner.startsWith("javax.") ||
            owner.startsWith("dalvik.")
    }

    /** Epoch-agnostic long accumulator; reads in whatever scale the caller stored. */
    private class AtomicCounter {
        private val cell = java.util.concurrent.atomic.AtomicLong(0)
        val value: Long
            get() = cell.get()
        fun reset() = cell.set(0)
        fun increment() = cell.incrementAndGet()
        fun add(delta: Long) = cell.addAndGet(delta)
    }
}
