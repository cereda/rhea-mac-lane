// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

// the destroy escalation's grace: the graceful kill gets this long
// before the forceful one lands
private val DESTROY_GRACE = 500.milliseconds

/** The capture's raw facts: what the drains harvested, post-mortem. */
private class Captures(
    val exitCode: Int,
    val combined: String,
    val stdout: String?,
    val stderr: String?,
)

/**
 * The JVM actual: the standard `ProcessBuilder`, the capture riding
 * its streams. MERGED joins stderr into stdout at the builder's
 * level (`redirectErrorStream` — the kernel-adjacent join, the
 * ordering as close to the child's writes as the JVM offers);
 * SEPARATE captures the two streams and pumps them concurrently —
 * the sequential drain deadlocks on a chatty child.
 */
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult {
    val builder = ProcessBuilder(listOf(executable) + args)
    when (val stdin = options.stdin) {
        Stdin.Inherit -> {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
        }

        Stdin.Closed -> {
            builder.redirectInput(
                ProcessBuilder.Redirect.from(File(nullDevice())),
            )
        }

        is Stdin.From -> {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE)
        }
    }
    options.directory?.let { builder.directory(File(it)) }
    if (options.environment.isNotEmpty()) {
        builder.environment().putAll(options.environment)
    }
    if (options.capture == CaptureMode.MERGED) {
        // the join happens BEFORE the spawn: the builder's own
        // stderr-into-stdout fold
        builder.redirectErrorStream(true)
    }

    val process =
        try {
            builder.start()
        } catch (expected: IOException) {
            throw ProcessException(
                "The command $executable could not run: ${expected.message}",
                expected,
            )
        }

    // the JVM-exit destroyer: a hung child does not survive the
    // caller's process (zt-exec's ShutdownHookProcessDestroyer's
    // shape)
    val shutdownHook = Thread { process.destroy() }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    // the timeout's watchdog: the graceful kill first, the
    // forceful escalation after the grace; the flag's verdict is
    // read AFTER the capture — never at the call
    val timedOut =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    options.timeout?.let { timeout ->
        Thread {
            Thread.sleep(timeout.inWholeMilliseconds)
            if (process.isAlive) {
                timedOut.set(true)
                process.destroy()
                if (!process.waitFor(DESTROY_GRACE.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    try {
        // the pumped stdin: a writer thread feeds the bytes and
        // closes the stream — the child reads at its own pace
        (options.stdin as? Stdin.From)?.let { from ->
            val writer =
                Thread {
                    process.outputStream.use { it.write(from.bytes) }
                }
            writer.isDaemon = true
            writer.start()
        }

        val captures =
            when (options.capture) {
                CaptureMode.MERGED -> mergedCapture(process, options.sink)
                CaptureMode.SEPARATE -> separateCapture(process, options.sink)
            }

        // the verdict: read the watchdog's flag HERE — the capture's
        // end (the destroy's EOF) is the earliest honest moment
        return if (timedOut.get()) {
            ProcessResult.Destroyed(captures.combined, DestroyReason.TIMEOUT)
        } else {
            ProcessResult.Completed(captures.exitCode, captures.combined, captures.stdout, captures.stderr)
        }
    } finally {
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
    }
}

/**
 * The separate capture: one lock, three buffers — the shared
 * buffer's append order IS the arrival order (the combined view's
 * near-true merge), the attributed buffers keep the streams apart.
 * The sink (when present) hears every line with its attribution.
 */
private fun separateCapture(
    process: Process,
    sink: Sink?,
): Captures {
    val lock = Any()
    val output = StringBuilder()
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val pumps =
        listOf(
            process.inputStream to Stream.OUT,
            process.errorStream to Stream.ERR,
        ).map { (stream, origin) ->
            pump(stream) { line ->
                synchronized(lock) {
                    output.appendLine(line)
                    when (origin) {
                        Stream.OUT -> stdout.appendLine(line)
                        else -> stderr.appendLine(line)
                    }
                    sink?.accept(line, origin)
                }
            }
        }
    pumps.forEach { it.join() }
    reap(process)
    return Captures(
        process.exitValue(),
        output.toString().trimEnd('\n'),
        stdout.toString().trimEnd('\n'),
        stderr.toString().trimEnd('\n'),
    )
}

/**
 * The merged capture: the builder's fold already merged the
 * streams — the lines arrive with the [Stream.UNKNOWN]
 * attribution (the boundary refuses to guess).
 */
private fun mergedCapture(
    process: Process,
    sink: Sink?,
): Captures {
    val output = StringBuilder()
    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.forEach { line ->
            output.appendLine(line)
            sink?.accept(line, Stream.UNKNOWN)
        }
    }
    reap(process)
    return Captures(process.exitValue(), output.toString().trimEnd('\n'), null, null)
}

/**
 * The platform's null device: the immediate-end-of-stream stdin.
 */
private fun nullDevice(): String =
    when (System.getProperty("os.name").lowercase()) {
        "windows" -> "NUL"
        else -> "/dev/null"
    }

/**
 * The reaping: the streams drained, the process waited to the end.
 */
private fun reap(process: Process) {
    process.waitFor()
    process.outputStream.close()
    process.errorStream.close()
}

/**
 * A pump: one thread draining a stream line by line into the
 * [sink] — the deadlock's antidote.
 */
private fun pump(
    stream: java.io.InputStream,
    sink: (String) -> Unit,
): Thread =
    Thread {
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { sink(it) }
        }
    }.apply {
        isDaemon = true
        start()
    }
