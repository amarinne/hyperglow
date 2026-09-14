package com.eza.hyperglow.root.symbols

import com.eza.hyperglow.root.HookLogger
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.WeakHashMap
import java.util.zip.ZipFile

/**
 * Loads `libdexkit.so` into the SystemUI process and owns one DexKit bridge per classpath apk.
 *
 * Measured on the owner device: `DexKitBridge.create(loader, useMemoryDexFile)` came back with
 * zero dexes on the Xiaomi SystemUI loaders, so the bridge is opened per classpath apk instead.
 * The stock SystemUI plugin class loader serves the base SystemUI apk plus the keyguard plugin
 * apk, and Xiaomi's AOD dex arrives on its own loader; a bridge is opened for each path the
 * loader's dex path list names, and the scope is logged so the comparison arms stay verifiable.
 */
object DexKitRuntime {
    private const val LIB_NAME = "dexkit"
    private const val TAG = "DexKitRuntime"

    // APK paths are stable deduplication keys. Loader bookkeeping must remain collectible.
    private val apkBridges = HashMap<String, DexKitBridge>()
    private val loaderBridges = WeakHashMap<ClassLoader, List<DexKitBridge>>()
    private var nativeLoaded = false
    private var libraryLoadAttempted = false
    private var extractionFailures = 0
    private const val NATIVE_FAIL_LIMIT = 3

    @Synchronized
    fun clearBridges() {
        apkBridges.values.forEach { it.close() }
        apkBridges.clear()
        loaderBridges.clear()
        // A loaded library remains mapped in this process; never truncate or reload its file.
    }

    @Synchronized
    fun hasPermanentResult(loader: ClassLoader): Boolean =
        loaderBridges.containsKey(loader) || extractionFailures >= NATIVE_FAIL_LIMIT

    @Synchronized
    fun ensureBridges(
        module: XposedInterface,
        hostClassLoader: ClassLoader,
        hostContext: android.content.Context? = null
    ): List<DexKitBridge> {
        loaderBridges[hostClassLoader]?.let { return it }
        val classpath = enumerate(hostClassLoader).filter {
            it.endsWith(".apk") || it.endsWith(".jar")
        }.distinct()
        if (classpath.isEmpty()) {
            loaderBridges[hostClassLoader] = emptyList()
            return emptyList()
        }
        // Do not cache an empty result while native loading is waiting for application context.
        if (!loadNative(module, hostContext)) return emptyList()
        val created = ArrayList<DexKitBridge>()
        for (path in classpath) {
            val existing = apkBridges[path]
            if (existing != null) {
                created.add(existing)
                continue
            }
            val started = System.nanoTime()
            try {
                val bridge = DexKitBridge.create(path)
                if (bridge.getDexNum() == 0) {
                    bridge.close()
                    continue
                }
                apkBridges[path] = bridge
                created.add(bridge)
                HookLogger.i(TAG, "bridge created path=$path dexNum=${bridge.getDexNum()}")
            } catch (error: Exception) {
                HookLogger.w(TAG, "bridge failed path=$path", error)
            } finally {
                SymbolSourceLog.bridgeNanos(System.nanoTime() - started)
            }
        }
        loaderBridges[hostClassLoader] = created
        return created
    }

    /** Walks the loader's dex path list; the field is declared on BaseDexClassLoader. */
    private fun enumerate(hostClassLoader: ClassLoader): List<String> {
        return try {
            var scope: Class<*>? = hostClassLoader.javaClass
            var pathList: Any? = null
            while (scope != null && pathList == null) {
                pathList = try {
                    scope.getDeclaredField("pathList").apply { isAccessible = true }.get(hostClassLoader)
                } catch (_: NoSuchFieldException) {
                    null
                }
                scope = scope.superclass
            }
            pathList ?: return emptyList()
            val dexElements = pathList.javaClass.getDeclaredField("dexElements").apply {
                isAccessible = true
            }.get(pathList) as? Array<*> ?: return emptyList()
            val paths = ArrayList<String>()
            for (element in dexElements) {
                element?.let { paths.add(elementFieldToString(it)) }
            }
            paths
        } catch (error: Exception) {
            HookLogger.w(TAG, "classpath unavailable loader=${hostClassLoader.javaClass.simpleName}", error)
            emptyList()
        }
    }

    private fun elementFieldToString(element: Any): String {
        for (name in arrayOf("path", "file", "zipFile")) {
            val value = try {
                element.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(element)
            } catch (_: NoSuchFieldException) {
                continue
            }
            when (value) {
                is File -> return value.absolutePath
                is String -> return value
                is java.util.zip.ZipFile -> return value.name
                is java.nio.file.Path -> return value.toString()
                else -> continue
            }
        }
        // No named field surfaced a path; the element still tells its owner.
        return element.javaClass.simpleName
    }

    private fun loadNative(
        module: XposedInterface,
        hostContext: android.content.Context?
    ): Boolean {
        if (nativeLoaded) return true
        if (!libraryLoadAttempted) {
            libraryLoadAttempted = true
            try {
                System.loadLibrary(LIB_NAME)
                nativeLoaded = true
                return true
            } catch (_: UnsatisfiedLinkError) {
                // Expected when LSPosed does not expose the module's APK native search path.
            } catch (error: SecurityException) {
                HookLogger.w(TAG, "native search path unavailable", error)
            }
        }
        // Pre-application misses do not consume the bounded extraction attempts.
        if (hostContext == null || extractionFailures >= NATIVE_FAIL_LIMIT) return false
        try {
            val path = resolveExtractDir(hostContext)
            extractLib(module.getModuleApplicationInfo().sourceDir, path)
            System.load(path.absolutePath)
            nativeLoaded = true
            return true
        } catch (error: UnsatisfiedLinkError) {
            HookLogger.w(TAG, "native extraction could not load", error)
        } catch (error: Exception) {
            HookLogger.w(TAG, "native extraction unavailable", error)
        }
        extractionFailures += 1
        return false
    }

    private fun extractLib(moduleApk: String, target: File) {
        val abi = BuildAbi.current
        ZipFile(File(moduleApk)).use { zip ->
            // The dependency packages the library under its full name: lib/<abi>/libdexkit.so.
            val entry = zip.getEntry("lib/$abi/lib$LIB_NAME.so")
                ?: throw IllegalStateException("libdexkit.so missing for abi=$abi")
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output ->
                    check(input.copyTo(output) > 0) { "empty native library" }
                }
            }
        }
    }

    /** A private, writable location is required; /data/local/tmp is not writable by SystemUI. */
    private fun resolveExtractDir(hostContext: android.content.Context?): File {
        val base = hostContext?.codeCacheDir
            ?: throw IllegalStateException("no host context for extraction dir")
        return File(base, "libdexkit.so")
    }

    private object BuildAbi {
        val current: String
            get() = android.os.Build.SUPPORTED_ABIS.firstOrNull {
                if (android.os.Process.is64Bit()) it == "arm64-v8a" || it == "x86_64"
                else it == "armeabi-v7a" || it == "x86"
            } ?: throw IllegalStateException("no supported process ABI")
    }
}
