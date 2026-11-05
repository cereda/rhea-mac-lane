// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import java.io.File
import java.io.IOException

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
    when (options.stdin) {
        // the null device: the child reads an immediate end of
        // stream (a bare PIPE would block — nobody holds its write
        // end)
        StdinPolicy.CLOSED -> {
            builder.redirectInput(
                ProcessBuilder.Redirect.from(File(nullDevice())),
            )
        }

        StdinPolicy.INHERIT -> {
            builder.redirectInput(
                ProcessBuilder.Redirect.INHERIT,
            )
        }
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

    return when (options.capture) {
        CaptureMode.MERGED -> {
            val output = process.inputStream.bufferedReader().useLines { it.joinToString("\n") }
            reap(process)
            ProcessResult.Completed(process.exitValue(), output)
        }

        CaptureMode.SEPARATE -> {
            separateCapture(process)
        }
    }
}

/**
 * The separate capture: one lock, three buffers — the shared
 * buffer's append order IS the arrival order (the combined view's
 * near-true merge), the attributed buffers keep the streams apart.
 */
private fun separateCapture(process: Process): ProcessResult.Completed {
    val lock = Any()
    val output = StringBuilder()
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val pumps =
        listOf(
            pump(process.inputStream) { line ->
                synchronized(lock) {
                    output.appendLine(line)
                    stdout.appendLine(line)
                }
            },
            pump(process.errorStream) { line ->
                synchronized(lock) {
                    output.appendLine(line)
                    stderr.appendLine(line)
                }
            },
        )
    pumps.forEach { it.join() }
    reap(process)
    return ProcessResult.Completed(
        process.exitValue(),
        output.toString().trimEnd('\n'),
        stdout.toString().trimEnd('\n'),
        stderr.toString().trimEnd('\n'),
    )
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
        stream.bufferedReader().useLines { lines ->
            lines.forEach { sink(it) }
        }
    }.apply {
        isDaemon = true
        start()
    }
