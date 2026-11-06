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
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.EINTR
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.SIG_IGN
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
import platform.posix.free
import platform.posix.kill
import platform.posix.malloc
import platform.posix.nanosleep
import platform.posix.open
import platform.posix.pipe
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_join
import platform.posix.pthread_self
import platform.posix.pthread_tVar
import platform.posix.putenv
import platform.posix.read
import platform.posix.signal
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
    const val SIGPIPE = 13

    // the waitpid's WNOHANG flag and the ECHILD errno: the POSIX
    // headers' own numbers (identical on Linux and macOS)
    const val WNOHANG = 1
    const val ECHILD = 10

    // the reap's spin for the watchdog's record: 20 ticks of the
    // completion's currency (a second, the record being a few
    // instructions behind the kernel's answer)
    const val REAP_SPIN_LIMIT = 20

    // the orphan fence's keeper probe: the park's tick (the fence's
    // watcher outlives the child, cheaply)
    const val ORPHAN_PROBE_TICK_MS = 250L
}

/**
 * The capture's three buffers and the sinks they feed: the combined
 * buffer's order IS the drains' arrival order (the mutex's
 * serialization), the attributed buffers keep the streams apart.
 * The mutex lives in the run's scope (the caller's memScoped).
 * The ears serialize: the line sink and the byte sink hear one
 * face at a time (the two drains' race never reaches the
 * caller's own machinery), and the throw's funeral releases the
 * lock (the dying thread never holds it).
 */
private class CaptureBuffers(
    val sink: Sink?,
    private val byteSink: ByteSink?,
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
        try {
            combined.appendLine(line)
            when (stream) {
                Stream.OUT -> stdout.appendLine(line)
                else -> stderr.appendLine(line)
            }
            sink?.accept(line, stream)
        } finally {
            platform.posix.pthread_mutex_unlock(mutex)
        }
    }

    /** The byte ears' face: the raw chunk under the same lock. */
    fun appendBytes(
        chunk: ByteArray,
        stream: Stream,
    ) {
        platform.posix.pthread_mutex_lock(mutex)
        try {
            byteSink?.accept(chunk, stream)
        } finally {
            platform.posix.pthread_mutex_unlock(mutex)
        }
    }

    /** The capture so far: the locked read (the exception's story). */
    fun combinedView(): String {
        platform.posix.pthread_mutex_lock(mutex)
        try {
            return combined.toString()
        } finally {
            platform.posix.pthread_mutex_unlock(mutex)
        }
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
 * The SIGPIPE's boot face: the write's broken pipe answers EPIPE
 * (the errno's own vocabulary, the boundary's ProcessException)
 * instead of the signal's silent process death. The JVM's own
 * precedent — its runtime ignores SIGPIPE globally — so the
 * native tiers speak the same law; the boundary arms it once,
 * lazily, on the first spawn.
 */
private val sigpipeIgnored = kotlin.concurrent.AtomicInt(0)

private fun ignoreSigpipe() {
    if (sigpipeIgnored.compareAndSet(0, 1)) {
        signal(PosixConstants.SIGPIPE, SIG_IGN)
    }
}

/**
 * The POSIX actual: `fork` + `execvp` (the argv speaks directly to
 * the kernel — no shell, no quoting) with the child's streams
 * riding pipes, drained concurrently by detached pthreads (the
 * drain's completion counting answers the join's question), and
 * the caller's timeout riding a detached watchdog whose graceful
 * kill escalates to the forceful one. The raw capture (NONE) skips
 * the pipes: the child's streams are the parent's own fds.
 */
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult {
    ignoreSigpipe()
    return memScoped {
        val merged = options.capture == CaptureMode.MERGED
        val captured = options.capture != CaptureMode.NONE
        val wiring = wirePipes(options, merged, captured)
        val pumpedStdin = (options.stdin as? Stdin.From)?.bytes

        // the capture's buffers: the mutex lives in this run's scope
        val runMutex = alloc<platform.posix.pthread_mutex_t>()
        platform.posix.pthread_mutex_init(runMutex.ptr, null)
        val buffers = CaptureBuffers(options.sink, options.byteSink, runMutex.ptr)

        val pid = fork()
        if (pid < 0) {
            // the fork's failure leaks the wiring's fds unless
            // closed — the boundary's failure face still owes the
            // table's hygiene
            closeWiring(wiring, captured, merged, pumpedStdin != null)
            throw ProcessException("fork failed: ${strerror(errno)?.toKString()}")
        }
        if (pid == 0) {
            // the orphan fence's child-side arm: the kernel's own
            // face (instant, unconditional) — first thing, before
            // anything that could fail and leave an orphan behind
            if (options.killOnParentDeath) {
                armParentDeathFence()
            }
            childExecutes(executable, args, options, wiring)
        }

        // the parent's side: the write ends close (the drains see EOF
        // when the child exits); the stdin's READ end dies here too —
        // the parent's own unread copy is a reader that never reads:
        // the pump's blocked write would outlive the child's death
        // (the big-stdin face's nine-minute hang, the lane's own
        // proof), and the child's death must break the pipe
        closeParentFaces(wiring, captured, merged, pumpedStdin != null)

        // the timeout's watchdog: the detached pthread's sleep, the
        // liveness probe, the graceful kill, the forceful escalation —
        // the flag's read happens after the capture's end. The state
        // lives here: the reap reads its answer when the probe's
        // waitpid reaped the fast child first.
        val timedOut = kotlin.concurrent.AtomicInt(0)
        val watchdog =
            options.timeout?.let { timeout ->
                WatchdogState(pid, timeout.inWholeMilliseconds, timedOut).also(::spawnWatchdog)
            }

        // the drains: the concurrent detached pthreads — the merged
        // pipe's one drain; the separate pair's two, each into its
        // buffer with the sink hearing every line attributed. Under
        // the raw capture no pipes exist: no drains.
        val finished = kotlin.concurrent.AtomicInt(0)
        if (captured) {
            spawnDrain(finished, wiring.outRead, buffers, if (merged) Stream.UNKNOWN else Stream.OUT)
            if (!merged) {
                spawnDrain(finished, wiring.errRead, buffers, Stream.ERR)
            }
        }

        // the stdin's pump: the write-then-drain's face — the bytes
        // fit the pipe's buffer (the small feeds' contract; the
        // chatty child's streams drain concurrently, so the child's
        // reads march on), the write's end closes for the child's
        // EOF. The broken-pipe face (the child's death mid-feed —
        // the timeout's ladder included) speaks through the verdict:
        // the pump's failure is the run's end, never an exception
        // that would swallow the Destroyed answer
        if (pumpedStdin != null) {
            try {
                writeAll(wiring.stdinWrite, pumpedStdin)
            } catch (expected: ProcessException) {
                // the child's gone: its death is the run's answer
            }
            close(wiring.stdinWrite)
        }

        // the join's answer: the completion counter's spin, 50ms
        // ticks — the helper speaks the drains' own count
        awaitDrains(finished, captured, merged)
        val exitCode = reap(pid, tolerateSignal = timedOut.value == 1, watchdog)

        // the verdict: the helper speaks the faces — the watchdog's
        // kill as Destroyed (its flag read here, after the capture's
        // end), the exit's decode otherwise
        return verdictFor(
            buffers,
            exitCode,
            reason = if (timedOut.value == 1) DestroyReason.TIMEOUT else null,
            attributed = !merged && captured,
        )
    }
}

/**
 * The join's answer: the completion counter's spin, 50ms ticks —
 * the drains' own count, none of them under the raw capture (the
 * spin's expectation must never count a drain that was never
 * spawned: the infinite face of the bug this comment guards).
 */
private fun awaitDrains(
    finished: kotlin.concurrent.AtomicInt,
    captured: Boolean,
    merged: Boolean,
) {
    val expected =
        if (captured) {
            if (merged) 1 else 2
        } else {
            0
        }
    while (finished.value < expected) {
        sleepMs(PosixConstants.COMPLETION_TICK_MS)
    }
}

/**
 * The parent's own faces' hygiene: the write ends close (the
 * drains see EOF when the child exits), and the stdin's read end
 * dies too — the parent's unread copy is a reader that never
 * reads: the pump's blocked write would outlive the child's
 * death (the big-stdin face's nine-minute hang, the lane's own
 * proof). The child's death must break the pipe.
 */
private fun closeParentFaces(
    wiring: PipeWiring,
    captured: Boolean,
    merged: Boolean,
    pumpedStdin: Boolean,
) {
    if (captured) {
        close(wiring.outWrite)
        if (!merged) {
            close(wiring.errWrite)
        }
    }
    if (pumpedStdin) {
        close(wiring.stdinRead)
    }
}

/**
 * The wiring's failure-face hygiene: the fds close (the spawn's
 * failure leaves nobody behind to close them — the child never
 * ran, the parent's own face never came).
 */
private fun closeWiring(
    wiring: PipeWiring,
    captured: Boolean,
    merged: Boolean,
    pumpedStdin: Boolean,
) {
    if (captured) {
        close(wiring.outRead)
        close(wiring.outWrite)
        if (!merged) {
            close(wiring.errRead)
            close(wiring.errWrite)
        }
    }
    if (pumpedStdin) {
        close(wiring.stdinRead)
        close(wiring.stdinWrite)
    }
}

/**
 * The verdict's faces: the ruled reason speaks Destroyed, the
 * exit's decode speaks the rest — the attributed fields answer
 * null unless the capture exists.
 */
private fun verdictFor(
    buffers: CaptureBuffers,
    exitCode: Int,
    reason: DestroyReason?,
    attributed: Boolean,
): ProcessResult =
    when (reason) {
        null -> {
            ProcessResult.Completed(
                exitCode,
                buffers.combined.toString().trimEnd('\n'),
                buffers.stdout
                    .toString()
                    .trimEnd('\n')
                    .takeUnless { !attributed },
                buffers.stderr
                    .toString()
                    .trimEnd('\n')
                    .takeUnless { !attributed },
            )
        }

        else -> {
            ProcessResult.Destroyed(buffers.combined.toString().trimEnd('\n'), reason)
        }
    }

/**
 * The pipes'-wiring's phase: the out/err pair (one under MERGED —
 * the kernel's own fold) + the stdin's pipe when the run pumps it.
 * Under the raw capture no capture pipes exist — only the stdin's
 * pump needs its own pair.
 */
private fun wirePipes(
    options: RunOptions,
    merged: Boolean,
    captured: Boolean,
): PipeWiring =
    memScoped {
        val outPipe = allocArray<IntVar>(2)
        val errPipe = allocArray<IntVar>(2)
        val stdinPipe = allocArray<IntVar>(2)
        var failed = false
        var outOpen = false
        var errOpen = false
        if (captured) {
            // pipe() creates both ends or neither: the partial
            // faces' hygiene tracks what exists
            outOpen = pipe(outPipe) == 0
            failed = !outOpen
            if (!failed && !merged) {
                errOpen = pipe(errPipe) == 0
                failed = !errOpen
            }
        }
        var stdinOpen = false
        if (!failed) {
            val pumpedStdin = (options.stdin as? Stdin.From)?.bytes
            if (pumpedStdin != null) {
                stdinOpen = pipe(stdinPipe) == 0
                failed = !stdinOpen
            }
        }
        if (failed) {
            // the created pairs close before the story: close()'s
            // own errno clobbers the pipe's, so the code travels
            // home first
            val code = errno
            if (stdinOpen) {
                close(stdinPipe[0])
                close(stdinPipe[1])
            }
            if (errOpen) {
                close(errPipe[0])
                close(errPipe[1])
            }
            if (outOpen) {
                close(outPipe[0])
                close(outPipe[1])
            }
            throw ProcessException("pipe failed: ${strerror(code)?.toKString()}")
        }
        PipeWiring(outPipe, errPipe, stdinPipe, merged)
    }

/**
 * The watchdog's packed state: the timeout's sleep, the probe's
 * answer, the ladder — and the reap's record. The probe's
 * `waitpid` reaps a zombie as a side effect: when the fast child
 * is gone before the timeout, the status travels home here (the
 * main thread's own `waitpid` would answer ECHILD).
 */
private class WatchdogState(
    val pid: Int,
    val ms: Long,
    val timedOut: kotlin.concurrent.AtomicInt,
    /**
     * The conversation's ruling int: the process's own writing
     * hand (null: the one-shot ladder's vocabulary rides `timedOut`
     * alone). The session's timeout watchdog shares it — the first
     * ruler's code is the verdict's.
     */
    val ruling: kotlin.concurrent.AtomicInt? = null,
    /** This watchdog's ruling code when its probe proves the child alive. */
    val reasonCode: Int = RULED_TIMEOUT,
) {
    /** 1 when this thread's probe reaped the child. */
    val reaped = kotlin.concurrent.AtomicInt(0)

    /** The reaped child's raw wait status (valid when [reaped] is 1). */
    val status = kotlin.concurrent.AtomicInt(0)
}

private fun spawnWatchdog(state: WatchdogState): Unit =
    memScoped {
        val ref = StableRef.create(state)
        pthread_create(
            alloc<platform.posix.pthread_tVar>().ptr,
            null,
            staticCFunction(::staticWatchdogRoutine),
            ref.asCPointer(),
        )
    }

private fun staticWatchdogRoutine(arg: kotlinx.cinterop.CPointer<*>?): kotlinx.cinterop.CPointer<*>? {
    pthread_detach(pthread_self())
    val ref = arg!!.asStableRef<WatchdogState>()
    val state = ref.get()
    memScoped {
        val wstatus = alloc<IntVar>()
        sleepMs(state.ms)
        val wait = waitpid(state.pid, wstatus.ptr, PosixConstants.WNOHANG)
        when {
            // still running: the ladder speaks (the flag's set FIRST
            // — the verdict's read happens after the capture's end);
            // the conversation's ruling: the first ruler's reason
            wait == 0 -> {
                state.timedOut.value = 1
                state.ruling?.compareAndSet(RULED_NONE, state.reasonCode)
                kill(state.pid, PosixConstants.SIGTERM)
                sleepMs(PosixConstants.DESTROY_GRACE_MS)
                kill(state.pid, PosixConstants.SIGKILL)
            }

            // the child's a zombie: the probe reaped it — the status
            // travels home, the kill ladder never starts
            wait > 0 -> {
                state.status.value = wstatus.value
                state.reaped.value = 1
            }

            // wait < 0 (ECHILD): the main thread reaped already —
            // nothing to kill, nothing to record
            else -> {}
        }
    }
    ref.dispose()
    return null
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
 * join's question. The ears ride the buffers' own lock: the byte
 * chunks and the lines serialize.
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
    drainFd(
        state.fd,
        ByteSink { chunk, face -> state.buffers.appendBytes(chunk, face) },
        state.stream,
    ) { line -> state.buffers.append(line, state.stream) }
    // the read end's own hygiene: the drain's done (the EOF's face
    // or the child's death) — the fd closes NOW (a leaked read fd
    // is the long-lived process's EMFILE, the fork's and the
    // pipe's own starvation)
    close(state.fd)
    state.finished.addAndGet(1)
    ref.dispose()
    return null
}

/**
 * The fd's drain: reads to EOF, feeding the [ByteSink] the raw
 * chunks and whole lines to the [sink] (the partial tail carries
 * between reads).
 */
private fun drainFd(
    fd: Int,
    byteSink: ByteSink?,
    stream: Stream,
    sink: (String) -> Unit,
) {
    val buffer = ByteArray(PosixConstants.BUFFER_SIZE)
    var carry = ""
    var eof = false
    while (!eof) {
        val count =
            read(
                fd,
                buffer.refTo(0),
                PosixConstants.BUFFER_SIZE.convert<size_t>(),
            )
        when {
            // the interrupted syscall is not the EOF's face: the
            // host's own signals never truncate the capture
            count < 0L && errno == EINTR -> {}

            count <= 0L -> {
                eof = true
            }

            else -> {
                // the raw face rides the copy: the decode/re-encode
                // round trip would corrupt a multi-byte sequence
                // split across the chunk's edge
                byteSink?.accept(buffer.copyOf(count.toInt()), stream)
                val chunk = buffer.decodeToString(0, count.toInt())
                val pieces = (carry + chunk).split('\n')
                carry = pieces.last()
                pieces.dropLast(1).forEach(sink)
            }
        }
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
        if (written < 0L && errno == EINTR) {
            // the interrupted syscall is not the break's face: retry
            continue
        }
        if (written <= 0L) {
            throw ProcessException("the session's stdin broke (the write failed)")
        }
        cursor += written.toInt()
    }
}

/**
 * The millisecond sleep: the nanosleep pair — the interrupted
 * sleep's remaining face rides nanosleep's own (the watchdog's
 * ladder never early, the keeper's park never short).
 */
private fun sleepMs(ms: Long) {
    memScoped {
        val ts = alloc<timespec>()
        ts.tv_sec = (ms / PosixConstants.MS_PER_SECOND).convert()
        ts.tv_nsec = ((ms % PosixConstants.MS_PER_SECOND) * PosixConstants.NS_PER_MS).convert()
        while (nanosleep(ts.ptr, ts.ptr) == -1 && errno == EINTR) {
            // the remaining time rides in ts (nanosleep's own face)
        }
    }
}

/**
 * The child's side: the working directory speaks (chdir), the
 * environment overrides apply (putenv over the inherited), the
 * pipes' write ends become the child's streams (both into one
 * under MERGED — the kernel's own fold; untouched under NONE —
 * the parent's own fds 1 and 2 are the child's), the stdin's
 * policy speaks, the pipe ends leave the child's table, then the
 * argv executes — the exec failure exits 254 (execvp returns only
 * on failure).
 */
private fun childExecutes(
    executable: String,
    args: List<String>,
    options: RunOptions,
    wiring: PipeWiring,
): Nothing =
    memScoped {
        // the streams' wiring speaks FIRST: the chdir's failure
        // story below rides fd 2, which is the captured pipe from
        // here on (the caller reads the story in the result) —
        // under the raw capture fd 2 IS the parent's stderr, the
        // story arrives in the user's console
        if (!wiring.merged) {
            if (options.capture != CaptureMode.NONE) {
                dup2(wiring.outWrite, STDOUT_FILENO)
                dup2(wiring.errWrite, 2)
            }
        } else {
            dup2(wiring.outWrite, STDOUT_FILENO)
            dup2(wiring.outWrite, 2) // the stderr joins the same pipe
        }
        options.directory?.let { dir ->
            if (chdir(dir) != 0) {
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

        when (options.stdin) {
            // the child inherits the parent's stdin: nothing to do
            Stdin.Inherit -> {
            }

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
        if (options.capture != CaptureMode.NONE) {
            // the capture's pipes leave the child's table (garbage
            // fd numbers otherwise: no pipes exist under NONE)
            close(wiring.outRead)
            close(wiring.outWrite)
            if (!wiring.merged) {
                close(wiring.errRead)
                close(wiring.errWrite)
            }
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
 * failure when it is 254), anything else died by the signal in
 * the low 7 bits. A tolerated signal (the watchdog's own kill)
 * answers -1: the caller speaks Destroyed. The watchdog's probe
 * may have reaped the fast child first: its recorded status
 * speaks then, for waitpid would answer ECHILD.
 */
private fun reap(
    pid: Int,
    tolerateSignal: Boolean,
    watchdog: WatchdogState?,
): Int {
    val status = harvestStatus(pid, watchdog)
    return decodeStatus(status, tolerateSignal)
}

/**
 * The wait status's harvest: the watchdog's record when its probe
 * reaped the fast child, the blocking waitpid otherwise — with a
 * brief spin for the record's flag when ECHILD catches the two
 * threads' race.
 */
private fun harvestStatus(
    pid: Int,
    watchdog: WatchdogState?,
): Int =
    when {
        watchdog?.reaped?.value == 1 -> watchdog.status.value
        else -> waitForStatus(pid, watchdog)
    }

/**
 * The blocking wait: the status, the ECHILD's spin for the
 * watchdog's record, or the failure's story.
 */
private fun waitForStatus(
    pid: Int,
    watchdog: WatchdogState?,
): Int =
    memScoped {
        val wstatus = alloc<IntVar>()
        val wait = waitpid(pid, wstatus.ptr, 0)
        when {
            wait >= 0 -> {
                wstatus.value
            }

            watchdog == null || errno != PosixConstants.ECHILD -> {
                throw ProcessException("waitpid failed: ${strerror(errno)?.toKString()}")
            }

            else -> {
                watchdogsHarvest(watchdog)
            }
        }
    }

/**
 * The record's wait: the watchdog reaped between the kernel's
 * answer and its flag (the record is a few instructions behind)
 * — the spin gives it a second to arrive.
 */
private fun watchdogsHarvest(watchdog: WatchdogState): Int {
    var spins = 0
    while (watchdog.reaped.value == 0 && spins < PosixConstants.REAP_SPIN_LIMIT) {
        sleepMs(PosixConstants.COMPLETION_TICK_MS)
        spins++
    }
    if (watchdog.reaped.value == 1) {
        return watchdog.status.value
    }
    throw ProcessException("the child's reaping lost: the watchdog's record never arrived")
}

/**
 * The status's decode: the failure's story or the exit code — the
 * factorization's own two faces, shared by both reaping roads.
 */
private fun decodeStatus(
    status: Int,
    tolerateSignal: Boolean,
): Int {
    val failure = failureOf(status, tolerateSignal)
    if (failure != null) {
        throw ProcessException(failure)
    }
    return (status shr PosixConstants.WSTATUS_EXIT_SHIFT) and PosixConstants.WSTATUS_EXIT_MASK
}

/**
 * The wait status's verdict: null for a healthy exit, the
 * failure's story otherwise (a signalled child when the boundary
 * doesn't tolerate it, or the exec failure's own 254 exit).
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

/**
 * The session's POSIX spawn: the fork's face with the stdin's
 * pipe always wired (the [RunOptions]' policy is not honored —
 * the forced `From` face wires the pipe and never feeds it: the
 * session's hand drives the write end), the drains riding
 * detached pthreads from the spawn's own moment, the timeout's
 * watchdog already patient.
 */
internal actual fun startSession(
    executable: String,
    args: List<String>,
    options: RunOptions,
): Session {
    ignoreSigpipe()
    val forced =
        options.copy(
            stdin = Stdin.From(ByteArray(0)), // the pipe's face; the bytes belong to the session's writes
        )
    return Session(PosixSessionDriver(executable, args, options, forced))
}

/**
 * The orphan fence's child-side arm: the platform's own face —
 * Linux's `PR_SET_PDEATHSIG` (the kernel kills the child when the
 * forking thread dies), the documented no-op where the kernel
 * offers nothing.
 */
internal expect fun armParentDeathFence()

/** The verdict's building face: the first hand's own CAS. */
private const val WAIT_BUILDING = 1

/** The verdict's built face: the second hand's answer rides it. */
private const val WAIT_BUILT = 2

/**
 * The POSIX conversation's machinery: the fork-backed spawn, the
 * malloc'd mutex's buffers (the driver outlives the constructor's
 * own scope), the drains live from the spawn's moment, the
 * timeout's watchdog, the destroyer's ladder riding its own
 * watchdog — the shared ruling's first ruler is the verdict's.
 */
private class PosixSessionDriver(
    private val executable: String,
    private val args: List<String>,
    private val options: RunOptions,
    private val forced: RunOptions,
) : SessionDriver {
    private val merged = options.capture == CaptureMode.MERGED
    private val captured = options.capture != CaptureMode.NONE
    private val finished = kotlin.concurrent.AtomicInt(0)
    private val ruling = kotlin.concurrent.AtomicInt(RULED_NONE)
    private val spoken = kotlin.concurrent.AtomicInt(0)
    private val wired = kotlin.concurrent.AtomicInt(0)
    private val building = kotlin.concurrent.AtomicInt(0)
    private var childPid: Int = -1
    private var stdinWrite: Int = -1
    private var sessionWiring: PipeWiring? = null
    private var wiringFailure: String? = null
    private val buffers: CaptureBuffers
    private val watchdog: WatchdogState?
    private val mutexStorage: kotlinx.cinterop.CPointer<platform.posix.pthread_mutex_t>

    /** The probe's own reap: the record when destroy's WNOHANG robs a zombie. */
    private val probed = kotlin.concurrent.AtomicInt(0)
    private val probedStatus = kotlin.concurrent.AtomicInt(0)
    private var verdict: ProcessResult? = null

    /** The child's pid (the session's own face of the number). */
    override val pid: Long
        get() = childPid.toLong()

    init {
        // the buffers' lock rides the heap: the driver outlives the
        // constructor's own scope (the memScoped alloc's face dies
        // with it), and the drains' appends ride the mutex
        mutexStorage =
            malloc(sizeOf<platform.posix.pthread_mutex_t>().convert())
                ?.reinterpret()
                ?: throw ProcessException("the session's lock starved (malloc failed)")
        platform.posix.pthread_mutex_init(mutexStorage, null)
        buffers = CaptureBuffers(options.sink, options.byteSink, mutexStorage)

        if (options.killOnParentDeath) {
            // the keeper's fork: the fence watches the forking
            // THREAD, and the session's caller returns at once — so
            // the fork rides a keeper thread that outlives the
            // child (the park below). The wiring comes home through
            // the fields; the caller waits for the wired flag.
            memScoped {
                val ref = StableRef.create(this@PosixSessionDriver)
                pthread_create(
                    alloc<platform.posix.pthread_tVar>().ptr,
                    null,
                    staticCFunction(::staticKeeperRoutine),
                    ref.asCPointer(),
                )
            }
            while (wired.value == 0) {
                sleepMs(PosixConstants.COMPLETION_TICK_MS)
            }
            wiringFailure?.let { failure ->
                platform.posix.pthread_mutex_destroy(mutexStorage)
                free(mutexStorage)
                throw ProcessException(failure)
            }
        } else {
            keeperFork()
        }
        val wiring =
            sessionWiring
                ?: run {
                    platform.posix.pthread_mutex_destroy(mutexStorage)
                    free(mutexStorage)
                    throw ProcessException("the session's wiring never arrived")
                }

        // the parent's side: the write ends close (the drains see
        // EOF when the child exits); the stdin's read end dies in
        // the parent too — the session keeps the write end (the
        // caller's hand)
        if (captured) {
            close(wiring.outWrite)
            if (!merged) {
                close(wiring.errWrite)
            }
        }
        close(wiring.stdinRead)
        stdinWrite = wiring.stdinWrite

        // the drains: the concurrent detached pthreads — under the
        // raw capture no pipes exist: no drains
        if (captured) {
            spawnDrain(finished, wiring.outRead, buffers, if (merged) Stream.UNKNOWN else Stream.OUT)
            if (!merged) {
                spawnDrain(finished, wiring.errRead, buffers, Stream.ERR)
            }
        }

        // the timeout's watchdog: the same detached ladder the
        // one-shot run rides, the ruling vocabulary TIMEOUT
        watchdog =
            options.timeout?.let {
                WatchdogState(
                    childPid,
                    it.inWholeMilliseconds,
                    kotlin.concurrent.AtomicInt(0),
                    ruling,
                    RULED_TIMEOUT,
                ).also(::spawnWatchdog)
            }
    }

    /**
     * The fork's phase, keeper-shaped: the pipes' wiring, the
     * fence's child-side arm (before anything that could fail and
     * leave an orphan), the exec's face — and the fence's park:
     * the watcher outlives the child (the `kill`'s zero-signal
     * probe answers the lifetime, no reaping interference; a
     * zombie keeps the park, the reaping being the session's own
     * business).
     */
    internal fun keeperFork() {
        try {
            val wiring = wirePipes(forced, merged, captured)
            val forked = fork()
            when {
                forked < 0 -> {
                    wiringFailure = "fork failed: ${strerror(errno)?.toKString()}"
                }

                forked == 0 -> {
                    if (options.killOnParentDeath) {
                        armParentDeathFence()
                    }
                    childExecutes(executable, args, forced, wiring)
                }

                else -> {
                    childPid = forked
                    sessionWiring = wiring
                }
            }
        } catch (failure: ProcessException) {
            wiringFailure = failure.message
        }
        wired.value = 1
        if (childPid > 0 && options.killOnParentDeath) {
            memScoped {
                while (kill(childPid, 0) == 0) {
                    sleepMs(PosixConstants.ORPHAN_PROBE_TICK_MS)
                }
            }
        }
    }

    override fun write(bytes: ByteArray) {
        check(spoken.value == 0) {
            "the session's stdin already spoke its end"
        }
        try {
            writeAll(stdinWrite, bytes)
        } catch (broken: ProcessException) {
            // the capture so far rides the exception (the promise's
            // own face — the story the child told before the break)
            throw ProcessException(broken.message ?: "the session's stdin broke", broken, buffers.combinedView())
        }
    }

    override fun endOfInput() {
        check(spoken.addAndGet(1) == 1) {
            "the session's stdin already spoke its end"
        }
        close(stdinWrite)
    }

    override fun destroy() {
        memScoped {
            val wstatus = alloc<IntVar>()
            val wait = waitpid(childPid, wstatus.ptr, PosixConstants.WNOHANG)
            when {
                // the still-alive child: the ruling + the inline
                // ladder (the caller's own hand — graceful first,
                // the forceful escalation after the grace)
                wait == 0 -> {
                    if (ruling.compareAndSet(RULED_NONE, RULED_DESTROYED)) {
                        kill(childPid, PosixConstants.SIGTERM)
                        sleepMs(PosixConstants.DESTROY_GRACE_MS)
                        kill(childPid, PosixConstants.SIGKILL)
                    }
                }

                // the exit just robbed: the probe reaped the zombie
                // — the record travels home (the child's own exit
                // speaks, not the boundary's hand)
                wait > 0 -> {
                    if (probed.compareAndSet(0, 1)) {
                        probedStatus.value = wstatus.value
                    }
                }

                // wait < 0: the timeout watchdog's record or the
                // main's wait — nothing to kill, nothing to rule
                else -> {}
            }
        }
    }

    override fun waitFor(): ProcessResult {
        // the single-shot face: the mutex's destroy and the heap's
        // free live here — a second wait's re-entry would ride
        // freed memory (the corruption's own face), so the verdict
        // caches and the re-ask answers it; the CONCURRENT ask
        // rides the building gate — the first hand builds, the
        // second's patience answers the same verdict
        verdict?.let { return it }
        val built =
            if (building.compareAndSet(0, WAIT_BUILDING)) {
                // the conversation's faces reclaim: the unspoken
                // stdin's fd closes (no finalizers on the native
                // tiers — a leaked fd is the fork's own starvation);
                // the spoken end's fd closed in endOfInput
                if (spoken.value == 0) {
                    close(stdinWrite)
                }
                awaitDrains(finished, captured, merged)
                val code = ruling.value
                val exitCode =
                    reapSession(childPid, tolerateSignal = code != RULED_NONE, watchdog, probed, probedStatus)
                val answer = verdictFor(buffers, exitCode, reasonOf(code), attributed = captured && !merged)
                platform.posix.pthread_mutex_destroy(mutexStorage)
                free(mutexStorage)
                verdict = answer
                building.value = WAIT_BUILT
                answer
            } else {
                // the second hand's patience: the built face's
                // atomic orders the plain var's read (the
                // watchdog's own shape)
                while (building.value != WAIT_BUILT) {
                    sleepMs(PosixConstants.COMPLETION_TICK_MS)
                }
                null
            }
        return built ?: verdict!!
    }
}

/**
 * The keeper's start routine: the C face of the Kotlin keeper —
 * the detach, the fork's phase (the park included), the ref's
 * dispose (the park outlives everything else: the dispose never
 * comes until the child's gone).
 */
private fun staticKeeperRoutine(arg: kotlinx.cinterop.CPointer<*>?): kotlinx.cinterop.CPointer<*>? {
    pthread_detach(pthread_self())
    val ref = arg!!.asStableRef<PosixSessionDriver>()
    val driver = ref.get()
    driver.keeperFork()
    ref.dispose()
    return null
}

/**
 * The conversation's reaping: the same factorization as
 * [reap], two more roads — whichever watchdog's probe reaped
 * first (the timeout's) or destroy's own WNOHANG rob answers
 * its record.
 */
private fun reapSession(
    pid: Int,
    tolerateSignal: Boolean,
    watchdog: WatchdogState?,
    probed: kotlin.concurrent.AtomicInt,
    probedStatus: kotlin.concurrent.AtomicInt,
): Int {
    val status =
        when {
            watchdog?.reaped?.value == 1 -> watchdog.status.value
            probed.value == 1 -> probedStatus.value
            else -> waitSessionStatus(pid, watchdog, probed, probedStatus)
        }
    return decodeStatus(status, tolerateSignal)
}

/**
 * The blocking wait's session face: the status, the ECHILD's spin
 * across the watchdog's record and destroy's own — the first to
 * arrive answers.
 */
private fun waitSessionStatus(
    pid: Int,
    watchdog: WatchdogState?,
    probed: kotlin.concurrent.AtomicInt,
    probedStatus: kotlin.concurrent.AtomicInt,
): Int =
    memScoped {
        val wstatus = alloc<IntVar>()
        val wait = waitpid(pid, wstatus.ptr, 0)
        when {
            wait >= 0 -> {
                wstatus.value
            }

            errno != PosixConstants.ECHILD -> {
                throw ProcessException("waitpid failed: ${strerror(errno)?.toKString()}")
            }

            else -> {
                var spins = 0
                while (watchdog?.reaped?.value == 0 && probed.value == 0 && spins < PosixConstants.REAP_SPIN_LIMIT) {
                    sleepMs(PosixConstants.COMPLETION_TICK_MS)
                    spins++
                }
                when {
                    watchdog?.reaped?.value == 1 -> {
                        watchdog.status.value
                    }

                    probed.value == 1 -> {
                        probedStatus.value
                    }

                    else -> {
                        throw ProcessException("the child's reaping lost: the watchdog's record never arrived")
                    }
                }
            }
        }
    }
