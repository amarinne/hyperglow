package com.eza.hyperglow.diagnostics

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs the command with the first `su` that can be spawned, because a root manager does not always
 * leave one where an app's `PATH` can find it and a spawn failure is indistinguishable from a
 * refusal once it reaches the report: both arrive as `error`, and an `error` suppresses the whole
 * capture, so the report carries no evidence. `su` on `PATH` stays first because that is where the
 * common managers bind it; the absolute paths cover the ones that install elsewhere.
 *
 * [run] answers `null` only when the binary could not be spawned at all, which is the single
 * outcome that justifies trying the next candidate. A command that ran and was refused, and a
 * command that ran too long, both answer the question and end the search.
 */
internal fun runFirstRootBinary(
    binaries: List<String>,
    run: (String) -> DiagnosticRootCommandResult?
): DiagnosticRootCommandResult = binaries.firstNotNullOfOrNull(run)
    ?: DiagnosticRootCommandResult(exitCode = -1, output = "")

internal object DiagnosticRootProcessRunner : DiagnosticRootCommandRunner {
    override fun run(command: String, timeoutMs: Long): DiagnosticRootCommandResult =
        runFirstRootBinary(ROOT_BINARIES) { binary ->
            runAs(binary, command, timeoutMs)
        }

    /** `null` when the binary could not be spawned, which is the only answer that continues the search. */
    private fun runAs(
        binary: String,
        command: String,
        timeoutMs: Long
    ): DiagnosticRootCommandResult? {
        var process: Process? = null
        var reader: Thread? = null
        val capture = RollingCommandOutput()
        return try {
            process = ProcessBuilder(binary, "-c", command)
                .redirectErrorStream(true)
                .start()
            val runningProcess = process
            reader = Thread {
                try {
                    runningProcess.inputStream.use(capture::read)
                } catch (_: IOException) {
                    capture.readFailed = true
                }
            }.apply {
                isDaemon = true
                name = "HyperGlowDiagnosticCommand"
                start()
            }
            val finished = runningProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) runningProcess.destroyForcibly()
            reader.join(READER_JOIN_MS)
            DiagnosticRootCommandResult(
                exitCode = if (finished && !capture.readFailed) runningProcess.exitValue() else -1,
                output = capture.asString(),
                timedOut = !finished,
                outputTruncated = capture.truncated
            )
        } catch (_: Exception) {
            null
        } finally {
            process?.destroy()
            reader?.interrupt()
        }
    }

    private class RollingCommandOutput {
        private val prefix = ArrayList<Byte>(PREFIX_BYTES)
        private val tail = ByteArray(TAIL_BYTES)
        private var tailCount = 0
        private var tailIndex = 0
        private var totalBytes = 0L

        @Volatile
        var readFailed = false

        val truncated: Boolean
            get() = totalBytes > PREFIX_BYTES + TAIL_BYTES

        fun read(input: InputStream) {
            val buffer = ByteArray(8 * 1024)
            while (!Thread.currentThread().isInterrupted) {
                val read = input.read(buffer)
                if (read < 0) break
                for (index in 0 until read) append(buffer[index])
            }
        }

        private fun append(value: Byte) {
            totalBytes++
            if (prefix.size < PREFIX_BYTES) {
                prefix += value
                return
            }
            tail[tailIndex] = value
            tailIndex = (tailIndex + 1) % TAIL_BYTES
            if (tailCount < TAIL_BYTES) tailCount++
        }

        fun asString(): String {
            val prefixBytes = prefix.toByteArray()
            if (tailCount == 0) return prefixBytes.toString(Charsets.UTF_8)
            val tailBytes = ByteArray(tailCount)
            val start = if (tailCount == TAIL_BYTES) tailIndex else 0
            for (index in tailBytes.indices) {
                tailBytes[index] = tail[(start + index) % TAIL_BYTES]
            }
            val separator = if (truncated) {
                "\n--- COMMAND OUTPUT MIDDLE OMITTED ---\n"
            } else {
                ""
            }
            return prefixBytes.toString(Charsets.UTF_8) + separator +
                tailBytes.toString(Charsets.UTF_8)
        }
    }

    private val ROOT_BINARIES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su"
    )

    private const val PREFIX_BYTES = 128 * 1024
    private const val TAIL_BYTES = 1024 * 1024
    private const val READER_JOIN_MS = 1_000L
}
