// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import java.io.File
import java.io.IOException
import java.io.InputStream
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
 * The builder's phase: the stdin's policy, the working directory,
 * the environment's overrides, the capture's fold — and the spawn
 * itself (the boundary's failure wearing ProcessException).
 */
private fun buildProcess(
    executable: String,
    args: List<String>,
    options: RunOptions,
): Process {
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
    when (options.capture) {
        CaptureMode.MERGED -> {
            // the join happens BEFORE the spawn: the builder's own
            // stderr-into-stdout fold
            builder.redirectErrorStream(true)
        }

        CaptureMode.NONE -> {
            // the raw terminal's face: the parent's handles are the
            // child's, no capture exists
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        }

        CaptureMode.SEPARATE -> {}
    }

    return try {
        builder.start()
    } catch (expected: IOException) {
        throw ProcessException(
            "The command $executable could not run: ${expected.message}",
            expected,
        )
    }
}

/**
 * The JVM actual: the standard `ProcessBuilder`, the capture riding
 * its streams. MERGED joins stderr into stdout at the builder's
 * level (`redirectErrorStream` — the kernel-adjacent join, the
 * ordering as close to the child's writes as the JVM offers);
 * SEPARATE captures the two streams and pumps them concurrently —
 * the sequential drain deadlocks on a chatty child; NONE hands
 * the streams to the parent's own handles (the builder's INHERIT
 * redirects) and the capture stays empty.
 */
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult {
    val process = buildProcess(executable, args, options)

    // the JVM-exit destroyer: a hung child does not survive the
    // caller's process (zt-exec's ShutdownHookProcessDestroyer's
    // shape) — the orphan fence's own law rides here too: the
    // caller's opt-out said the child lives (the POSIX and the
    // mingw tiers honor it in every face; so does the JVM now)
    val shutdownHook = Thread { process.destroy() }
    if (options.killOnParentDeath) {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

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
        // closes the stream — the child reads at its own pace. The
        // broken-pipe face (the child's death mid-feed — the
        // timeout's ladder included) speaks through the verdict:
        // the pump's failure is the run's end, never an uncaught
        // stack trace that would swallow the Destroyed answer
        (options.stdin as? Stdin.From)?.let { from ->
            val writer =
                Thread {
                    try {
                        process.outputStream.use { it.write(from.bytes) }
                    } catch (expected: IOException) {
                        // the child's gone: its death is the run's answer
                    }
                }
            writer.isDaemon = true
            writer.start()
        }

        val captures =
            when (options.capture) {
                CaptureMode.MERGED -> mergedCapture(process, options)
                CaptureMode.SEPARATE -> separateCapture(process, options)
                CaptureMode.NONE -> consoleCapture(process)
            }

        // the verdict: read the watchdog's flag HERE — the capture's
        // end (the destroy's EOF) is the earliest honest moment
        return if (timedOut.get()) {
            ProcessResult.Destroyed(captures.combined, DestroyReason.TIMEOUT)
        } else {
            ProcessResult.Completed(captures.exitCode, captures.combined, captures.stdout, captures.stderr)
        }
    } finally {
        // the removal's own shutdown-race: during the JVM's
        // shutdown the hook is the JVM's business (it runs anyway),
        // the IllegalStateException never swallows the verdict
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (expected: IllegalStateException) {
            // the JVM's own shutdown owns the hook now
        }
    }
}

/**
 * The separate capture: one lock, three buffers — the shared
 * buffer's append order IS the arrival order (the combined view's
 * near-true merge), the attributed buffers keep the streams apart.
 * The sinks hear every chunk and every line with its attribution.
 */
private fun separateCapture(
    process: Process,
    options: RunOptions,
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
            pump(stream, options.byteSink, origin, lock) { line ->
                synchronized(lock) {
                    output.appendLine(line)
                    when (origin) {
                        Stream.OUT -> stdout.appendLine(line)
                        else -> stderr.appendLine(line)
                    }
                    options.sink?.accept(line, origin)
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
    options: RunOptions,
): Captures {
    val output = StringBuilder()
    rawDrain(process.inputStream, options.byteSink, Stream.UNKNOWN, Any()) { line ->
        output.appendLine(line)
        options.sink?.accept(line, Stream.UNKNOWN)
    }
    reap(process)
    return Captures(process.exitValue(), output.toString().trimEnd('\n'), null, null)
}

/**
 * The console capture: the builder's INHERIT redirects already
 * handed the streams to the parent's handles — nothing to drain,
 * nothing to say. The wait alone speaks.
 */
private fun consoleCapture(process: Process): Captures {
    reap(process)
    return Captures(process.exitValue(), "", null, null)
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
 * The read streams close — the pumps' EOF is the drain's end, and
 * the JVM's Cleaner is not the boundary's patience (a leaked fd
 * starves the long-lived process's own). The stdin's hand owes
 * nothing: the writer closed it (the fed face's `use`), or no
 * pipe existed (the inherit/closed faces) — and the JDK's own
 * process-death swap already closed the dead pipe's fd.
 */
private fun reap(process: Process) {
    process.waitFor()
    process.inputStream.close()
    process.errorStream.close()
}

/** The drain's riding buffer: the read chunk's ceiling. */
private const val DRAIN_BUFFER_SIZE = 8_192

/**
 * The raw drain: reads the stream to EOF, feeding the [ByteSink]
 * the raw chunks (the byte face's law: no line split's waiting)
 * and whole lines to the [sink] (the partial tail carries between
 * reads). The byte ears ride the [lock]'s serialization — the
 * two drains' race never reaches the caller's own machinery.
 */
private fun rawDrain(
    stream: InputStream,
    byteSink: ByteSink?,
    attribution: Stream,
    lock: Any,
    sink: (String) -> Unit,
) {
    val buffer = ByteArray(DRAIN_BUFFER_SIZE)
    var carry = ""
    while (true) {
        val count = stream.read(buffer)
        if (count <= 0) {
            break
        }
        synchronized(lock) { byteSink?.accept(buffer.copyOf(count), attribution) }
        val chunk = buffer.decodeToString(0, count)
        val pieces = (carry + chunk).split('\n')
        carry = pieces.last()
        pieces.dropLast(1).forEach(sink)
    }
    if (carry.isNotEmpty()) {
        sink(carry)
    }
}

/**
 * A pump: one thread running the raw drain into the [sink] — the
 * deadlock's antidote.
 */
private fun pump(
    stream: InputStream,
    byteSink: ByteSink?,
    attribution: Stream,
    lock: Any,
    sink: (String) -> Unit,
): Thread =
    Thread {
        rawDrain(stream, byteSink, attribution, lock, sink)
    }.apply {
        isDaemon = true
        start()
    }

/**
 * The session's JVM spawn: the same builder's face, the stdin
 * always a pipe (the [RunOptions]' policy is not honored — the
 * forced `From` face wires the pipe and never auto-pumps: the
 * session's hand drives it), the drains riding daemon pumps from
 * the spawn's own moment.
 */
internal actual fun startSession(
    executable: String,
    args: List<String>,
    options: RunOptions,
): Session {
    val forced =
        options.copy(
            stdin = Stdin.From(ByteArray(0)), // the pipe's face; the bytes belong to the session's writes
        )
    return Session(JvmSessionDriver(buildProcess(executable, args, forced), options))
}

/**
 * The JVM conversation's machinery: the pump-backed drains, the
 * timeout's watchdog, the destroyer's ladder — the verdict reads
 * the ruling AFTER the capture's end, `execute`'s law.
 */
private class JvmSessionDriver(
    process: Process,
    options: RunOptions,
) : SessionDriver {
    private val process = process
    private val options = options
    private val ruling =
        java.util.concurrent.atomic
            .AtomicReference<DestroyReason?>(null)
    private val spoken =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val lock = Any()
    private val combined = StringBuilder()
    private val stdout = StringBuilder()
    private val stderr = StringBuilder()
    private val drains: List<Thread> =
        when (options.capture) {
            CaptureMode.MERGED -> {
                listOf(
                    pump(process.inputStream, options.byteSink, Stream.UNKNOWN, lock) { line ->
                        append(line, Stream.UNKNOWN)
                    },
                )
            }

            CaptureMode.SEPARATE -> {
                listOf(process.inputStream to Stream.OUT, process.errorStream to Stream.ERR).map { (stream, origin) ->
                    pump(stream, options.byteSink, origin, lock) { line -> append(line, origin) }
                }
            }

            CaptureMode.NONE -> {
                emptyList()
            }
        }
    private val shutdownHook = Thread { process.destroy() }

    /** The child's pid (the JVM's own face of the number). */
    override val pid: Long = process.pid()

    init {
        // the orphan fence's JVM face: the child dies with the
        // JVM — unless the daemon's door opened (the flag's false)
        if (options.killOnParentDeath) {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
        }

        // the timeout's watchdog: the same ladder `execute` speaks,
        // the ruling's vocabulary TIMEOUT
        options.timeout?.let {
            Thread {
                Thread.sleep(it.inWholeMilliseconds)
                if (process.isAlive && ruling.compareAndSet(null, DestroyReason.TIMEOUT)) {
                    destroyLadder()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun write(bytes: ByteArray) {
        // the spoken end's own face: the write after the EOF's
        // speech answers the contract's IllegalStateException — the
        // child may live; the caller closed their own hand (the
        // old face answered the child's-gone lie on a closed stream)
        check(!spoken.get()) {
            "the session's stdin already spoke its end"
        }
        // the uniform broken-pipe face: the POSIX and the mingw
        // tiers answer ProcessException, so the JVM's own
        // IOException wears the same vocabulary
        try {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        } catch (broken: IOException) {
            // the capture so far rides the exception (the promise's
            // own face — the story the child told before the break)
            throw ProcessException(
                "the session's stdin broke (the child's gone)",
                broken,
                synchronized(lock) { combined.toString() },
            )
        }
    }

    override fun endOfInput() {
        check(spoken.compareAndSet(false, true)) {
            "the session's stdin already spoke its end"
        }
        process.outputStream.close()
    }

    override fun destroy() {
        // the ruling lands before the ladder: the verdict's reader
        // can never spill into an unruled end-of-conversation
        if (process.isAlive && ruling.compareAndSet(null, DestroyReason.DESTROYED)) {
            Thread { destroyLadder() }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private var verdict: ProcessResult? = null

    override fun waitFor(): ProcessResult {
        // the single-shot face: the verdict caches (the re-ask
        // answers it — the POSIX tier's cache is the corruption's
        // own guard, this one its consistency)
        verdict?.let { return it }
        drains.forEach { it.join() }
        process.waitFor()
        // the conversation's faces reclaim: the drained streams
        // close (the JVM's Cleaner is not the boundary's patience).
        // The stdin's hand owes nothing here: the spoken end's own
        // closed in endOfInput, and the unspoken one's fd died with
        // the child (the JDK's own process-death swap — a close on
        // the swapped stream would answer the flush with the
        // dirty-buffer throw)
        process.inputStream.close()
        process.errorStream.close()
        val reason: DestroyReason? = ruling.get()
        val built =
            when (reason) {
                null -> {
                    val attributed = options.capture == CaptureMode.SEPARATE
                    ProcessResult.Completed(
                        process.exitValue(),
                        combined.toString().trimEnd('\n'),
                        stdout.toString().trimEnd('\n').takeUnless { !attributed },
                        stderr.toString().trimEnd('\n').takeUnless { !attributed },
                    )
                }

                else -> {
                    ProcessResult.Destroyed(combined.toString().trimEnd('\n'), reason)
                }
            }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (expected: IllegalStateException) {
            // the JVM's own shutdown owns the hook now (it runs
            // anyway) — the verdict rides home regardless
        }
        verdict = built
        return built
    }

    /** The capture's append: the lock's serialization, the sinks' ears. */
    private fun append(
        line: String,
        stream: Stream,
    ) {
        synchronized(lock) {
            combined.appendLine(line)
            when (stream) {
                Stream.OUT -> {
                    stdout.appendLine(line)
                }

                Stream.ERR -> {
                    stderr.appendLine(line)
                }

                Stream.UNKNOWN -> {}
            }
            options.sink?.accept(line, stream)
        }
    }

    /** The boundary's ladder: the graceful kill, the grace, the forceful. */
    private fun destroyLadder() {
        process.destroy()
        if (!process.waitFor(DESTROY_GRACE.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
    }
}
