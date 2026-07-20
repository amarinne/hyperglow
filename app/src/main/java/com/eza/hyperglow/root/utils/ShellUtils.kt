package com.eza.hyperglow.root.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/** Root process restart path extracted from HyperLyric. */
object ShellUtils {
    suspend fun restartSystemUI(): Boolean = killAppProcess("com.android.systemui")

    private suspend fun killAppProcess(packageName: String, signal: Int = 15): Boolean {
        val script = $$"""
            pid=$(pgrep -f "$$packageName" | grep -v $$)
            if [ -z "$pid" ]; then
                pids=""
                pid=$(ps -A -o PID,ARGS=CMD | grep "$$packageName" | grep -v "grep")
                for i in $pid; do
                    case "$i" in
                        ''|*[!0-9]*) ;;
                        *) pids="$pids $i" ;;
                    esac
                done
                pid=$pids
            fi

            killed=0
            if [ -n "$pid" ]; then
                for i in $pid; do
                    kill -s $$signal "$i" >/dev/null 2>&1
                    kill -s 9 "$i" >/dev/null 2>&1
                    if [ $? -eq 0 ]; then
                        killed=1
                    fi
                done
            fi

            if [ $killed -eq 1 ]; then
                exit 0
            else
                exit 1
            fi
        """.trimIndent()

        return execRootScriptSilent("nsenter --mount=/proc/1/ns/mnt -- sh", script)
    }

    private suspend fun execRootScriptSilent(cmd: String, inputScript: String): Boolean =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            var output: DataOutputStream? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                output = DataOutputStream(process.outputStream)
                output.write(inputScript.toByteArray(Charsets.UTF_8))
                output.writeBytes("\nexit\n")
                output.flush()
                process.waitFor() == 0
            } catch (_: Exception) {
                false
            } finally {
                runCatching { output?.close() }
                process?.destroy()
            }
        }
}
