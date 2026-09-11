package com.eza.hyperglow.root

import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

/**
 * Generation-owned registry for every SystemUI hook handle.
 *
 * Stable hook ids (`feature + target identity`) allow deterministic replacement, and one
 * retire point gives hot reload a single place to remove the previous generation. All
 * hookers run in [ExceptionMode.PROTECTIVE] so a throwing callback continues the chain
 * as if the hook were absent.
 */
internal object HookRegistry {
    private val handles = LinkedHashMap<String, HookHandle>()
    private var generation = 0L

    @Synchronized
    fun hook(
        module: XposedModule,
        feature: String,
        target: Executable,
        hooker: Hooker
    ): HookHandle {
        val id = hookIdFor(feature, target)
        module.deoptimize(target)
        val handle = module.hook(target)
            .setId(id)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(hooker)
        handles.remove(id)?.let { previous ->
            runCatching { previous.unhook() }
        }
        handles[id] = handle
        return handle
    }

    /** Removes every handle owned by the current generation. Returns the retired count. */
    @Synchronized
    fun retireGeneration(): Int {
        val retired = handles.size
        for (handle in handles.values) {
            runCatching { handle.unhook() }
        }
        handles.clear()
        generation++
        return retired
    }

    /** Removes handles owned by a previous module generation, e.g. after hot reload. */
    fun unhookPrevious(handles: List<HookHandle?>): Int {
        var unhooked = 0
        for (handle in handles) {
            if (handle == null) continue
            runCatching { handle.unhook() }.onSuccess { unhooked++ }
        }
        return unhooked
    }

    @Synchronized
    fun activeCount(): Int = handles.size

    @Synchronized
    fun currentGeneration(): Long = generation
}

/**
 * Stable hook id for one feature/target pair. Pure string mapping so id stability
 * is host-testable without the framework.
 */
internal fun hookIdFor(feature: String, target: Executable): String {
    val owner = target.declaringClass?.name ?: "unknown"
    val name = if (target is Constructor<*>) "<init>" else target.name
    val params = target.parameterTypes.joinToString(",") { it.name }
    return "hyperglow:$feature:$owner#$name($params)"
}
