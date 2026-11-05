// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.CLOCK_MONOTONIC
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.chdir
import platform.posix.clock_gettime
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fork
import platform.posix.kill
import platform.posix.open
import platform.posix.pipe
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_join
import platform.posix.pthread_self
import platform.posix.pthread_tVar
import platform.posix.putenv
import platform.posix.read
import platform.posix.size_t
import platform.posix.strerror
import platform.posix.timespec
import platform.posix.waitpid
import platform.posix.write
import kotlin.time.Duration

/** The POSIX boundary's fixed numbers (the spec's own constants). */
private object PosixConstants {
    // the buffer a read rides in
    const val BUFFER_SIZE = 8_192

    // the exec failure's exit status: RHEA'S OWN MARKER (254, the
    // value the shell conventions never claim) — the tool's own
    // 127 stays the tool's answer, never mistaken for the
    // boundary's failure
    const val EXIT_COULD_NOT_EXECUTE = 254

    // the wait status factorization's masks (the low 7 bits name
    // the signal, the high byte holds the exit code)
    const val WSTATUS_MASK = 0x7f
    const val WSTATUS_EXIT_SHIFT = 8
    const val WSTATUS_EXIT_MASK = 0xff

    // the destroy escalation's grace + the completion's tick
    const val DESTROY_GRACE_MS = 500L
    const val COMPLETION_TICK_MS = 50L

    // the time's currency
    const val MS_PER_SECOND = 1000L
    const val NS_PER_MS = 1_000_000L

    // the signals' POSIX numbers: fixed by the spec, portable by
    // definition (the kill's own vocabulary)
    const val SIGTERM = 15
    const val SIGKILL = 9
}

/**
 * The capture's three buffers and the sink they feed: the combined
 * buffer's order IS the drains' arrival order (the mutex's
 * serialization), the attributed buffers keep the streams apart.
 * The mutex lives in the run's scope (the caller's memScoped).
 */
private class CaptureBuffers(
    val sink: Sink?,
    val mutex: kotlinx.cinterop.CPointer<platform.posix.pthread_mutex_t>,
) {
    val combined = StringBuilder()
    val stdout = StringBuilder()
    val stderr = StringBuilder()

    /** The thread-safe append: the combined's order = the lock's. */
    fun append(
        line: String,
        stream: Stream,
    ) {
        platform.posix.pthread_mutex_lock(mutex)
        combined.appendLine(line)
        when (stream) {
            Stream.OUT -> stdout.appendLine(line)
            else -> stderr.appendLine(line)
        }
        sink?.accept(line, stream)
        platform.posix.pthread_mutex_unlock(mutex)
    }
}

/**
 * The pipes' descriptor ends: the parent's side, the child's side;
 * the stdin's pipe rides along when the run pumps it.
 */
private class PipeWiring(
    outPipe: CArrayPointer<IntVar>,
    errPipe: CArrayPointer<IntVar>,
    stdinPipe: CArrayPointer<IntVar>,
    val merged: Boolean,
) {
    val outRead = outPipe[0]
    val outWrite = outPipe[1]
    val errRead = errPipe[0]
    val errWrite = errPipe[1]
    val stdinRead = stdinPipe[0]
    val stdinWrite = stdinPipe[1]
}

/**
 * The POSIX actual: `fork` + `execvp` (the argv speaks directly to
 * the kernel — no shell, no quoting) with the child's streams
 * riding pipes, drained concurrently by detached pthreads (the
 * drain's completion counting answers the join's question), and
 * the caller's timeout riding a detached watchdog whose graceful
 * kill escalates to the forceful one.
 */
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult =
    memScoped {
        val merged = options.capture == CaptureMode.MERGED
        val wiring = wirePipes(options, merged)
        val pumpedStdin = (options.stdin as? Stdin.From)?.bytes

        // the capture's buffers: the mutex lives in this run's scope
        val runMutex = alloc<platform.posix.pthread_mutex_t>()
        platform.posix.pthread_mutex_init(runMutex.ptr, null)
        val buffers = CaptureBuffers(options.sink, runMutex.ptr)

        val pid = fork()
        if (pid < 0) {
            throw ProcessException("fork failed: ${strerror(errno)?.toKString()}")
        }
        if (pid == 0) {
            childExecutes(executable, args, options, wiring)
        }

        // the parent's side: the write ends close (the drains see EOF
        // when the child exits)
        close(wiring.outWrite)
        if (!merged) {
            close(wiring.errWrite)
        }

        // the timeout's watchdog: the detached pthread's sleep, the
        // liveness probe, the graceful kill, the forceful escalation —
        // the flag's read happens after the capture's end
        val timedOut = kotlin.concurrent.AtomicInt(0)
        options.timeout?.let { timeout ->
            spawnWatchdog(pid, timeout, timedOut)
        }

        // the drains: the concurrent detached pthreads — the merged
        // pipe's one drain; the separate pair's two, each into its
        // buffer with the sink hearing every line attributed
        val finished = kotlin.concurrent.AtomicInt(0)
        spawnDrain(finished, wiring.outRead, buffers, if (merged) Stream.UNKNOWN else Stream.OUT)
        if (!merged) {
            spawnDrain(finished, wiring.errRead, buffers, Stream.ERR)
        }

        // the stdin's pump: the write-then-drain's face — the bytes
        // fit the pipe's buffer (the small feeds' contract; the
        // chatty child's streams drain concurrently, so the child's
        // reads march on), the write's end closes for the child's EOF
        if (pumpedStdin != null) {
            writeAll(wiring.stdinWrite, pumpedStdin)
            close(wiring.stdinWrite)
        }

        // the join's answer: the completion counter's spin, 50ms ticks
        val expected = 1 + (if (merged) 0 else 1)
        while (finished.value < expected) {
            sleepMs(PosixConstants.COMPLETION_TICK_MS)
        }
        val exitCode = reap(pid, tolerateSignal = timedOut.value == 1)

        // the verdict: the watchdog's kill speaks Destroyed, the
        // wait's own decode speaks the rest — the flag's read happens
        // here, after the capture's end (never at the spawn's call)
        return when {
            timedOut.value == 1 -> {
                ProcessResult.Destroyed(buffers.combined.toString().trimEnd('\n'), DestroyReason.TIMEOUT)
            }

            else -> {
                ProcessResult.Completed(
                    exitCode,
                    buffers.combined.toString().trimEnd('\n'),
                    buffers.stdout
                        .toString()
                        .trimEnd('\n')
                        .takeUnless { merged },
                    buffers.stderr
                        .toString()
                        .trimEnd('\n')
                        .takeUnless { merged },
                )
            }
        }
    }

/**
 * The pipes'-wiring's phase: the out/err pair (one under MERGED —
 * the kernel's own fold) + the stdin's pipe when the run pumps it.
 */
private fun wirePipes(
    options: RunOptions,
    merged: Boolean,
): PipeWiring =
    memScoped {
        val outPipe = allocArray<IntVar>(2)
        val errPipe = allocArray<IntVar>(2)
        val stdinPipe = allocArray<IntVar>(2)
        if (pipe(outPipe) != 0 || (!merged && pipe(errPipe) != 0)) {
            throw ProcessException("pipe failed: ${strerror(errno)?.toKString()}")
        }
        val pumpedStdin = (options.stdin as? Stdin.From)?.bytes
        if (pumpedStdin != null && pipe(stdinPipe) != 0) {
            throw ProcessException("pipe failed: ${strerror(errno)?.toKString()}")
        }
        PipeWiring(outPipe, errPipe, stdinPipe, merged)
    }

/**
 * The watchdog's detached pthread: the timeout's sleep, the
 * liveness probe (waitpid's WNOHANG), the graceful kill, the
 * grace, the forceful one — the flag's set FIRST (the verdict's
 * read happens after the capture's end).
 */
private class WatchdogState(
    val pid: Int,
    val ms: Long,
    val timedOut: kotlin.concurrent.AtomicInt,
)

private fun spawnWatchdog(
    pid: Int,
    timeout: Duration,
    timedOut: kotlin.concurrent.AtomicInt,
): Unit =
    memScoped {
        val state = StableRef.create(WatchdogState(pid, timeout.inWholeMilliseconds, timedOut))
        pthread_create(
            alloc<platform.posix.pthread_tVar>().ptr,
            null,
            staticCFunction(::staticWatchdogRoutine),
            state.asCPointer(),
        )
    }

private fun staticWatchdogRoutine(arg: kotlinx.cinterop.CPointer<*>?): kotlinx.cinterop.CPointer<*>? {
    pthread_detach(pthread_self())
    val ref = arg!!.asStableRef<WatchdogState>()
    val state = ref.get()
    sleepMs(state.ms)
    if (testAlive(state.pid)) {
        state.timedOut.value = 1
        kill(state.pid, PosixConstants.SIGTERM)
        sleepMs(PosixConstants.DESTROY_GRACE_MS)
        kill(state.pid, PosixConstants.SIGKILL)
    }
    ref.dispose()
    return null
}

/**
 * The watchdog's liveness probe: waitpid's WNOHANG answers the
 * zombie's presence without blocking.
 */
private fun testAlive(pid: Int): Boolean {
    memScoped {
        val wstatus = alloc<IntVar>()
        val wait = waitpid(pid, wstatus.ptr, 1) // WNOHANG
        return wait == 0 // 0: still running; -1/pid: gone or reaped
    }
}

/**
 * The drain thread's packed state: the stable-ref's payload (the
 * C's start routine speaks one pointer).
 */
private class DrainState(
    val fd: Int,
    val buffers: CaptureBuffers,
    val stream: Stream,
    val finished: kotlin.concurrent.AtomicInt,
)

/**
 * Spawns a DETACHED pthread draining [fd] into the buffers (the
 * pthread_t's platform shape stays unnameable — the detached
 * threads owe no handles); the [finished] counter answers the
 * join's question.
 */
private fun spawnDrain(
    finished: kotlin.concurrent.AtomicInt,
    fd: Int,
    buffers: CaptureBuffers,
    stream: Stream,
): Unit =
    memScoped {
        val state = StableRef.create(DrainState(fd, buffers, stream, finished))
        pthread_create(
            alloc<platform.posix.pthread_tVar>().ptr,
            null,
            staticCFunction(::staticDrainRoutine),
            state.asCPointer(),
        )
    }

/**
 * The drain's start routine: the C face of the Kotlin drain — the
 * stable-ref's unpack, the drain, the completion's count, the
 * ref's dispose. The thread's own first act: the detach (the
 * handles' platform shapes stay unnameable).
 */
private fun staticDrainRoutine(arg: kotlinx.cinterop.CPointer<*>?): kotlinx.cinterop.CPointer<*>? {
    pthread_detach(pthread_self())
    val ref = arg!!.asStableRef<DrainState>()
    val state = ref.get()
    drainFd(state.fd) { line -> state.buffers.append(line, state.stream) }
    state.finished.addAndGet(1)
    ref.dispose()
    return null
}

/**
 * The fd's drain: reads to EOF, feeding whole lines to the
 * [sink] (the partial tail carries between reads).
 */
private fun drainFd(
    fd: Int,
    sink: (String) -> Unit,
) {
    val buffer = ByteArray(PosixConstants.BUFFER_SIZE)
    var carry = ""
    while (true) {
        val count =
            read(
                fd,
                buffer.refTo(0),
                PosixConstants.BUFFER_SIZE.convert<size_t>(),
            )
        if (count <= 0L) {
            break
        }
        val chunk = carry + buffer.decodeToString(0, count.toInt())
        val lines = chunk.split('\n')
        carry = lines.last()
        lines.dropLast(1).forEach(sink)
    }
    if (carry.isNotEmpty()) {
        sink(carry)
    }
}

/**
 * The write-all: the bytes feed the pipe (the partial writes
 * loop), the caller closes the fd for the child's EOF.
 */
private fun writeAll(
    fd: Int,
    bytes: ByteArray,
) {
    var cursor = 0
    while (cursor < bytes.size) {
        val written =
            write(
                fd,
                bytes.refTo(cursor),
                (bytes.size - cursor).convert<size_t>(),
            )
        if (written <= 0L) {
            throw ProcessException("the stdin's pump broke (write failed)")
        }
        cursor += written.toInt()
    }
}

/**
 * The millisecond sleep: the nanosleep pair.
 */
private fun sleepMs(ms: Long) {
    memScoped {
        val ts = alloc<timespec>()
        ts.tv_sec = (ms / PosixConstants.MS_PER_SECOND).convert()
        ts.tv_nsec = ((ms % PosixConstants.MS_PER_SECOND) * PosixConstants.NS_PER_MS).convert()
        platform.posix.nanosleep(ts.ptr, null)
    }
}

/**
 * The child's side: the working directory speaks (chdir), the
 * environment overrides apply (putenv over the inherited), the
 * pipes' write ends become the child's streams (both into one
 * under MERGED — the kernel's own fold), the stdin's policy
 * speaks, the pipe ends leave the child's table, then the argv
 * executes — the exec failure exits 127 (execvp returns only on
 * failure).
 */
private fun childExecutes(
    executable: String,
    args: List<String>,
    options: RunOptions,
    wiring: PipeWiring,
): Nothing =
    memScoped {
        options.directory?.let { dir ->
            if (chdir(dir) != 0) {
                // the child's own stderr rides the captured pipe: the
                // caller reads the story in the result
                val story =
                    "rhea: the working directory $dir refused: " +
                        (strerror(errno)?.toKString() ?: "unknown")
                write(2, story.cstr, story.length.convert())
                _exit(1)
            }
        }
        // the environment overrides ride putenv: the pointers live in
        // this scope, and the scope outlives the exec call
        options.environment.forEach { (key, value) ->
            putenv("$key=$value".cstr.ptr)
        }

        if (wiring.merged) {
            dup2(wiring.outWrite, STDOUT_FILENO)
            dup2(wiring.outWrite, 2) // the stderr joins the same pipe
        } else {
            dup2(wiring.outWrite, STDOUT_FILENO)
            dup2(wiring.errWrite, 2)
        }
        when (options.stdin) {
            Stdin.Inherit -> {
                // the child inherits the parent's stdin: nothing to do
            }

            // the child inherits the parent's stdin
            Stdin.Closed -> {
                // the null device: an immediate end of stream
                val nullFd = open("/dev/null", 0)
                dup2(nullFd, STDIN_FILENO)
                close(nullFd)
            }

            is Stdin.From -> {
                dup2(wiring.stdinRead, STDIN_FILENO)
                close(wiring.stdinRead)
                // the write end dies here too: the child's inherited
                // copy would hold the pipe open forever, and the
                // child's own read would never see its EOF
                close(wiring.stdinWrite)
            }
        }
        close(wiring.outRead)
        close(wiring.outWrite)
        if (!wiring.merged) {
            close(wiring.errRead)
            close(wiring.errWrite)
        }
        val argv = allocArray<kotlinx.cinterop.CPointerVar<ByteVar>>(args.size + 2)
        (listOf(executable) + args).forEachIndexed { index, word ->
            argv[index] = word.cstr.ptr
        }
        argv[args.size + 1] = null
        execvp(executable, argv)
        _exit(PosixConstants.EXIT_COULD_NOT_EXECUTE)
        // the exec's failure exits above; this line never runs, but
        // the compiler's flow analysis wants the Nothing spelled
        error("unreachable")
    }

/**
 * The reaping: the wait status decodes through the factorization
 * — the low byte zero is a clean exit (whose code speaks the exec
 * failure when it is 127), anything else died by the signal in
 * the low 7 bits. A tolerated signal (the watchdog's own kill)
 * answers -1: the caller speaks Destroyed.
 */
private fun reap(
    pid: Int,
    tolerateSignal: Boolean,
): Int {
    memScoped {
        val wstatus = alloc<IntVar>()
        if (waitpid(pid, wstatus.ptr, 0) < 0) {
            throw ProcessException("waitpid failed: ${strerror(errno)?.toKString()}")
        }
        val status = wstatus.value
        val failure = failureOf(status, tolerateSignal)
        if (failure != null) {
            throw ProcessException(failure)
        }
        return (status shr PosixConstants.WSTATUS_EXIT_SHIFT) and PosixConstants.WSTATUS_EXIT_MASK
    }
}

/**
 * The wait status's verdict: null for a healthy exit, the
 * failure's story otherwise (a signalled child when the boundary
 * doesn't tolerate it, or the exec failure's own 127 exit).
 */
private fun failureOf(
    status: Int,
    tolerateSignal: Boolean,
): String? =
    when (val raw = status and PosixConstants.WSTATUS_MASK) {
        0 -> {
            when ((status shr PosixConstants.WSTATUS_EXIT_SHIFT) and PosixConstants.WSTATUS_EXIT_MASK) {
                PosixConstants.EXIT_COULD_NOT_EXECUTE -> {
                    "the command could not execute (is it in the system path?)"
                }

                else -> {
                    null
                }
            }
        }

        else -> {
            if (tolerateSignal) {
                null
            } else {
                "the command died from signal $raw"
            }
        }
    }
