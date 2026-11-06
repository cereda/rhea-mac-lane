// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UShortVarOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.AssignProcessToJobObject
import platform.windows.CREATE_UNICODE_ENVIRONMENT
import platform.windows.CloseHandle
import platform.windows.CreateJobObjectW
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.CreateThread
import platform.windows.DWORD
import platform.windows.DWORDVar
import platform.windows.FALSE
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.JOBOBJECT_BASIC_LIMIT_INFORMATION
import platform.windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
import platform.windows.PROCESS_INFORMATION
import platform.windows.QueryInformationJobObject
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetHandleInformation
import platform.windows.SetInformationJobObject
import platform.windows.Sleep
import platform.windows.TRUE
import platform.windows.TerminateProcess
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile
import kotlin.time.Duration

/** The Windows boundary's fixed numbers. */
private object WinConstants {
    // the buffer a read rides in
    const val BUFFER_SIZE = 8_192

    // the destroy escalation's grace: TerminateProcess is the
    // kill's only face (no graceful signal exists), so the grace
    // is the child's own cleanup's chance
    const val DESTROY_GRACE_MS = 500u

    // the verdict's gate: the first hand's CAS (building), the
    // second hand's answer (built), the patience's tick between
    const val WAIT_BUILDING = 1
    const val WAIT_BUILT = 2
    const val WAIT_TICK_MS = 50u
}

/**
 * The pipes' four faces: the reads' (the parent's drains) and the
 * writes' (the child's streams, the pump's feed); the stdin's
 * pair rides along when the run pumps it.
 */
private class PipeHandles(
    val outRead: HANDLE?,
    val outWrite: HANDLE?,
    val errRead: HANDLE?,
    val errWrite: HANDLE?,
    val stdin: Pair<HANDLE?, HANDLE?>? = null, // the stdin's (read, write) pair
    val merged: Boolean = false,
)

/**
 * The capture's three views: the drains append, the verdict
 * speaks. The drains' shared lock rides here — the spin's own
 * face (the POSIX tier's pthread mutex's mirror without the
 * memory's lifecycle: the drains hold the lock through the
 * faces, the GC keeps it while they live). The sections are the
 * appends' own: microseconds, the yield's face between.
 */
private class CaptureViews {
    val lock = DrainLock()
    val combined = StringBuilder()
    val stdout = StringBuilder()
    val stderr = StringBuilder()
}

/** The spin's own lock: the CAS's test-and-set, the yield between. */
private class DrainLock {
    private val held = kotlin.concurrent.AtomicInt(0)

    fun <T> around(block: () -> T): T {
        while (!held.compareAndSet(0, 1)) {
            Sleep(0u)
        }
        try {
            return block()
        } finally {
            held.value = 0
        }
    }
}

/**
 * One drain's face: the views it appends, the sinks it speaks,
 * the stream it attributes — the packed state's payload. The
 * ears ride the views' lock: the caller's sink hears one line
 * at a time, the byte chunks arrive one at a time (the two
 * drains' race never reaches the caller's own machinery), and
 * the combined view's order is the lock's own.
 */
private class DrainFace(
    views: CaptureViews,
    private val attributed: StringBuilder,
    byteSink: ByteSink?,
    val stream: Stream,
    sink: Sink?,
) {
    private val combined = views.combined
    private val lock = views.lock
    val byteSink: ByteSink? =
        byteSink?.let { own ->
            ByteSink { chunk, face -> lock.around { own.accept(chunk, face) } }
        }
    private val lineSink: Sink? =
        sink?.let { own ->
            Sink { line, face -> lock.around { own.accept(line, face) } }
        }

    /** The locked append: the views' order = the lock's order. */
    fun append(line: String) {
        lock.around {
            combined.appendLine(line)
            attributed.appendLine(line)
            lineSink?.accept(line, stream)
        }
    }
}

/**
 * The drain thread's packed state: the stable-ref's payload (the
 * Win32's start routine speaks one pointer).
 */
private class DrainState(
    val read: HANDLE?,
    val face: DrainFace,
    val finished: kotlin.concurrent.AtomicInt,
)

/**
 * The drains' thread handles: the spawn returns, the join waits.
 */
private class DrainThreads(
    val out: HANDLE?,
    val err: HANDLE?,
    val stdin: HANDLE?,
)

/**
 * The pump thread's packed state: the bytes feed the child's
 * stdin's pipe, the write's end closes for the child's EOF.
 */
private class PumpState(
    val write: HANDLE?,
    val bytes: ByteArray,
    val finished: kotlin.concurrent.AtomicInt,
)

/**
 * The Win32 actual: `CreateProcessW` with the child's streams
 * riding anonymous pipes, drained concurrently by Win32 threads —
 * the JVM's pumps' mirror, the deadlock's antidote. The caller's
 * timeout rides the process-wait's own budget: the expiry speaks
 * `TerminateProcess` (Windows' kill has no graceful face) and the
 * verdict speaks `Destroyed`. The raw capture (NONE) skips the
 * pipes: the child's streams are the parent's own handles
 * (console faces included).
 */
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult =
    memScoped {
        val merged = options.capture == CaptureMode.MERGED
        val raw = options.capture == CaptureMode.NONE

        // the pipes': the handles' phase (none exist under the raw
        // capture — only the stdin's pump needs its own pair)
        val pipes = createPipes(options, merged, raw)

        // the orphan fence's job: the child's life rides the
        // parent's (the handle's close speaks the kill — the
        // parent's death included)
        val job =
            if (options.killOnParentDeath) {
                try {
                    createOrphanJob()
                } catch (failure: ProcessException) {
                    closePipeHandles(pipes)
                    throw failure
                }
            } else {
                null
            }

        // the spawn's phase: the child's process, the stdio's faces
        val process =
            try {
                spawnChild(executable, args, options, pipes, job)
            } catch (failure: ProcessException) {
                // the spawn's failure leaks the handles unless
                // closed — the boundary's failure face still owes
                // the table's hygiene
                job?.let { CloseHandle(it) }
                closePipeHandles(pipes)
                throw failure
            }

        // the parent's write-end copies close BEFORE the reading: a
        // leaked handle turns the drain into a deadlock
        if (!raw) {
            CloseHandle(pipes.outWrite)
            if (!merged) {
                CloseHandle(pipes.errWrite)
            }
        }
        pipes.stdin?.let { CloseHandle(it.first) }

        // the drains: the concurrent Win32 threads — the merged
        // pipe's one drain; the separate pair's two, each into its
        // buffer with the sink hearing every line attributed; plus
        // the pumped stdin's thread (the bytes feed, the write's
        // end closes for the child's EOF). Under the raw capture no
        // pipes exist: no drains.
        val views = CaptureViews()
        val threads = spawnDrains(pipes, options, views, merged, raw)

        // the wait's budget: the expiry speaks TerminateProcess (the
        // Windows' kill's only face)
        val timedOut = waitForChild(process.hProcess, options.timeout)

        // the join's answer: the threads' handles wait, then close
        WaitForSingleObject(threads.out, INFINITE)
        CloseHandle(threads.out)
        threads.err?.let { thread ->
            WaitForSingleObject(thread, INFINITE)
            CloseHandle(thread)
        }
        threads.stdin?.let { thread ->
            WaitForSingleObject(thread, INFINITE)
            CloseHandle(thread)
        }

        val exitCode = alloc<DWORDVar>()
        GetExitCodeProcess(process.hProcess, exitCode.ptr)
        CloseHandle(process.hProcess)
        CloseHandle(process.hThread)

        // the job's hygiene: the child's done (killed or exited) —
        // the fence's handle has nothing left to guard
        job?.let { CloseHandle(it) }

        // the verdict: the helper speaks the faces — the watchdog's
        // kill as Destroyed, the exit's decode otherwise
        return verdictFor(
            views,
            exitCode.value.toInt(),
            reason = if (timedOut) DestroyReason.TIMEOUT else null,
            attributed = !merged && !raw,
        )
    }

/**
 * The verdict's faces: the ruled reason speaks Destroyed, the
 * exit's decode speaks the rest — the attributed fields answer
 * null unless the capture exists.
 */
private fun verdictFor(
    views: CaptureViews,
    exitCode: Int,
    reason: DestroyReason?,
    attributed: Boolean,
): ProcessResult =
    when (reason) {
        null -> {
            ProcessResult.Completed(
                exitCode,
                views.combined.toString().trimEnd('\n'),
                views.stdout
                    .toString()
                    .trimEnd('\n')
                    .takeUnless { !attributed },
                views.stderr
                    .toString()
                    .trimEnd('\n')
                    .takeUnless { !attributed },
            )
        }

        else -> {
            ProcessResult.Destroyed(views.combined.toString().trimEnd('\n'), reason)
        }
    }

/**
 * The pipes' handle hygiene: the parent's own ends close on the
 * spawn's failure (the child never ran — nobody else will).
 */
private fun closePipeHandles(pipes: PipeHandles) {
    pipes.outRead?.let { CloseHandle(it) }
    pipes.outWrite?.let { CloseHandle(it) }
    if (!pipes.merged) {
        pipes.errRead?.let { CloseHandle(it) }
        pipes.errWrite?.let { CloseHandle(it) }
    }
    pipes.stdin?.let { (read, write) ->
        read?.let { CloseHandle(it) }
        write?.let { CloseHandle(it) }
    }
}

/**
 * The stdio's three faces: the child's handles as plain values —
 * the stdin's face = the pumped pipe's read end, the parent's
 * real stdin under Inherit, the nothing's face under Closed; the
 * out/err faces = the pipes' write ends, the parent's own
 * handles under the raw capture.
 */
private fun stdioFaces(
    options: RunOptions,
    pipes: PipeHandles,
): StdioFaces {
    val merged = options.capture == CaptureMode.MERGED
    val raw = options.capture == CaptureMode.NONE
    return StdioFaces(
        input =
            when (options.stdin) {
                // the parent's real stdin: the interactive
                // tools' floor (GetStdHandle, not the
                // nothing's fiction)
                Stdin.Inherit -> GetStdHandle(STD_INPUT_HANDLE)

                Stdin.Closed -> INVALID_HANDLE_VALUE

                is Stdin.From -> pipes.stdin?.first
            },
        output = if (raw) GetStdHandle(STD_OUTPUT_HANDLE) else pipes.outWrite,
        error =
            when {
                raw -> GetStdHandle(STD_ERROR_HANDLE)
                merged -> pipes.outWrite
                else -> pipes.errWrite
            },
    )
}

/**
 * The stdio's faces as values: the scoped struct's own payload
 * (the handles ride memory that never leaves the spawn's scope).
 */
private class StdioFaces(
    val input: HANDLE?,
    val output: HANDLE?,
    val error: HANDLE?,
)

/**
 * The spawn's call: CreateProcessW's own face — the block's null
 * speaks the parent's environment, the directory's null the
 * parent's cwd.
 */
private fun spawnProcess(
    arguments: SpawnArguments,
    startup: CPointer<STARTUPINFOW>,
    process: CPointer<PROCESS_INFORMATION>,
): Int =
    CreateProcessW(
        null,
        arguments.commandLine,
        null,
        null,
        TRUE,
        arguments.creationFlags,
        arguments.environment,
        arguments.directory,
        startup,
        process,
    )

/**
 * The spawn's packed words: the command line, the environment's
 * block (the null speaks the parent's own), the directory's face,
 * the creation flags.
 */
private class SpawnArguments(
    val commandLine: CPointer<UShortVarOf<UShort>>?,
    val environment: CPointer<UShortVarOf<UShort>>?,
    val directory: CPointer<UShortVarOf<UShort>>?,
    val creationFlags: DWORD,
)

/**
 * The environment's block: the parent's inherited face plus the
 * overrides — one string with the embedded nulls, riding the
 * command line's own proven UTF-16 encode, the block's double
 * null at its end (the string's own terminator completes it).
 */
private fun environmentString(overrides: Map<String, String>): String {
    val inherited =
        GetEnvironmentStringsW()
    val texts =
        if (inherited != null) {
            val read = readPairTexts(inherited)
            FreeEnvironmentStringsW(inherited)
            read
        } else {
            emptyList()
        }
    // the overrides REPLACE the inherited faces: the block with a
    // duplicated key answers the CRT's own undefined pick, so the
    // inherited pairs whose key collides (case-insensitively — the
    // Windows environment's own law) leave the block first. The
    // whole block then sorts case-insensitively — the block's own
    // documented requirement, and the sort's face answers the
    // lazily-appended faces too
    val keys = overrides.keys.map { it.uppercase() }.toSet()
    val kept =
        texts.filter { text ->
            text.substringBefore('=').uppercase() !in keys
        }
    val block = kept + overrides.map { (key, value) -> "$key=$value" }
    return block.sortedBy { it.substringBefore('=').uppercase() }.joinToString("\u0000") + "\u0000"
}

/**
 * The inherited block's walk: the UTF-16 unit strings until the
 * double null — the pairs' texts, verbatim.
 */
private fun readPairTexts(inherited: CPointer<UShortVarOf<UShort>>): List<String> {
    val texts = mutableListOf<String>()
    var cursor = 0
    while (true) {
        val text = StringBuilder()
        while (true) {
            val unit = inherited[cursor].toInt()
            if (unit == 0) {
                break
            }
            text.append(Char(unit))
            cursor++
        }
        cursor++
        if (text.isEmpty()) {
            break // the block's double null: the end
        }
        texts += text.toString()
    }
    return texts
}

/**
 * The orphan fence's job: the kernel's own answer — when the
 * parent's process dies, the OS closes the job's last handle and
 * the kernel kills the whole tree (the strict face: no
 * breakaway — a child that wants out of the job gets the
 * documented refusal).
 *
 * Two binding lies ride this face, both earned on the lanes:
 * the `SetInformationJobObject`'s return answers FALSE while the
 * write lands (the Query's read-back proves it — the 8192's own
 * face), and the generated class vals answer 0-based while
 * winnt.h's JOBOBJECTINFOCLASS starts at 1 (the val *named*
 * BasicLimitInformation = 2, the accounting struct's face — the
 * kernel's 87). So the class rides the header's own number and
 * the Set's return is never trusted: the Query's read-back is
 * the only truth, and its failure is loud.
 */
private const val JOB_OBJECT_INFO_BASIC_LIMITS: UInt = 3u

private fun createOrphanJob(): HANDLE? {
    val job = CreateJobObjectW(null, null)
    if (job == null || job == INVALID_HANDLE_VALUE) {
        throw ProcessException("CreateJobObjectW failed (code ${GetLastError()})")
    }
    memScoped {
        val limits = alloc<JOBOBJECT_BASIC_LIMIT_INFORMATION>()
        limits.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.convert<DWORD>()
        val size = sizeOf<JOBOBJECT_BASIC_LIMIT_INFORMATION>().convert<DWORD>()
        SetInformationJobObject(job, JOB_OBJECT_INFO_BASIC_LIMITS, limits.ptr, size)
        if (
            QueryInformationJobObject(job, JOB_OBJECT_INFO_BASIC_LIMITS, limits.ptr, size, null) == FALSE ||
            limits.LimitFlags != JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.convert<DWORD>()
        ) {
            CloseHandle(job)
            throw ProcessException(
                "the job's kill-on-close never landed (query says ${limits.LimitFlags}, code ${GetLastError()})",
            )
        }
    }
    return job
}

/**
 * The pipes'-phase: the out/err pair (one under MERGED — the
 * kernel's own fold) + the stdin's pipe when the run pumps it.
 * Under the raw capture no capture pipes exist — the handles stay
 * null, only the stdin's pump needs its own pair.
 */
private fun createPipes(
    options: RunOptions,
    merged: Boolean,
    raw: Boolean,
): PipeHandles =
    memScoped {
        val security =
            alloc<SECURITY_ATTRIBUTES>().apply {
                nLength = sizeOf<SECURITY_ATTRIBUTES>().convert<DWORD>()
                bInheritHandle = TRUE
            }
        var outRead: HANDLE? = null
        var outWrite: HANDLE? = null
        var errRead: HANDLE? = null
        var errWrite: HANDLE? = null
        if (!raw) {
            val outPair = alloc<HANDLEVar>()
            val outPairWrite = alloc<HANDLEVar>()
            if (CreatePipe(outPair.ptr, outPairWrite.ptr, security.ptr, 0u) == FALSE) {
                throw ProcessException("CreatePipe failed (code ${GetLastError()})")
            }
            outRead = outPair.value
            outWrite = outPairWrite.value
            if (!merged) {
                val errPair = alloc<HANDLEVar>()
                val errPairWrite = alloc<HANDLEVar>()
                if (CreatePipe(errPair.ptr, errPairWrite.ptr, security.ptr, 0u) == FALSE) {
                    throw ProcessException("CreatePipe failed (code ${GetLastError()})")
                }
                errRead = errPair.value
                errWrite = errPairWrite.value
            }
        }
        val stdinRead = alloc<HANDLEVar>()
        val stdinWrite = alloc<HANDLEVar>()
        val pumpsStdin = options.stdin is Stdin.From
        if (pumpsStdin && CreatePipe(stdinRead.ptr, stdinWrite.ptr, security.ptr, 0u) == FALSE) {
            throw ProcessException("CreatePipe failed (code ${GetLastError()})")
        }
        if (pumpsStdin) {
            // the fork's hygiene's Windows face: the child inherits
            // the stdin pipe's READ end alone — the write end's
            // inherited copy would hold the pipe open forever, and
            // the child's own read would never see its EOF (the
            // deadlock's root, the same lesson the POSIX fork
            // learned on the forensics' cat graveyard)
            SetHandleInformation(stdinWrite.value, HANDLE_FLAG_INHERIT.convert<DWORD>(), 0u)
        }
        PipeHandles(
            outRead,
            outWrite,
            errRead,
            errWrite,
            stdinRead.value to stdinWrite.value,
            merged,
        )
    }

/**
 * The spawn's phase: the child's process, the stdio's faces (the
 * pipes' read ends; the stdin's face = the pumped pipe's read end,
 * the parent's real stdin under Inherit, the nothing's face under
 * Closed). Under the raw capture the out/err faces are the
 * parent's own handles: the console sees the child as it speaks.
 */
private fun spawnChild(
    executable: String,
    args: List<String>,
    options: RunOptions,
    pipes: PipeHandles,
    job: HANDLE? = null,
): PROCESS_INFORMATION =
    memScoped {
        // the stdio's faces, computed as plain values first — the
        // struct itself rides THIS scope (the arena's memory never
        // leaves it: the startupFor's returned wrapper was the
        // dangling face, the spawn reading the freed arena's
        // garbage handles — the lanes' instant ProcessExceptions
        // and the binary's crash)
        val faces = stdioFaces(options, pipes)
        val startup =
            alloc<STARTUPINFOW>().apply {
                cb = sizeOf<STARTUPINFOW>().convert<DWORD>()
                dwFlags = STARTF_USESTDHANDLES.convert<DWORD>()
                hStdInput = faces.input
                hStdOutput = faces.output
                hStdError = faces.error
            }
        val process = alloc<PROCESS_INFORMATION>()
        val commandLine = quote(listOf(executable) + args).wcstr.ptr
        val directory = options.directory?.let { dir -> dir.wcstr.ptr }
        // the fence's spawn face: the child rides the job (the
        // parent's death closes the job's handle, the kernel kills
        // the tree). The assignment lands directly after the
        // spawn: the escape race (the child's own first
        // microseconds) is accepted — the suspended-spawn dance
        // proved the poison pill on the matrix's lane (run #16's
        // instant-killed children and the crashed test binary)
        val environment =
            if (options.environment.isEmpty()) {
                null
            } else {
                environmentString(options.environment).wcstr.ptr
            }
        val created =
            spawnProcess(
                SpawnArguments(
                    commandLine,
                    environment,
                    directory,
                    CREATE_UNICODE_ENVIRONMENT.convert<DWORD>(),
                ),
                startup.ptr,
                process.ptr,
            )
        if (created == FALSE) {
            throw ProcessException(
                "CreateProcessW failed for $executable (code ${GetLastError()})",
            )
        }
        job?.let {
            if (AssignProcessToJobObject(it, process.hProcess) == FALSE) {
                // the fence's failure is loud: the caller asked for
                // the protection, the environment refused it
                throw ProcessException(
                    "AssignProcessToJobObject failed for $executable (code ${GetLastError()})",
                )
            }
        }
        process
    }

/**
 * The process-wait's budget: the caller's timeout, or the patient
 * INFINITE; the expiry speaks TerminateProcess (the Windows'
 * kill's only face) + the grace's wait (the child's own cleanup's
 * chance before the drains' EOF is forced).
 * @return whether the boundary destroyed the child.
 */
private fun waitForChild(
    hProcess: HANDLE?,
    timeout: Duration?,
): Boolean {
    val timeoutMs = timeout?.inWholeMilliseconds?.convert<DWORD>() ?: INFINITE
    val timedOut = WaitForSingleObject(hProcess, timeoutMs) == WAIT_TIMEOUT.convert<DWORD>()
    if (timedOut) {
        TerminateProcess(hProcess, 1u)
        WaitForSingleObject(hProcess, WinConstants.DESTROY_GRACE_MS)
    }
    return timedOut
}

/**
 * The drain thread's spawn: the C face of the Kotlin drain — the
 * stable-ref's payload (the Win32's start routine speaks one
 * pointer); the thread's handle answers for the join.
 */
private fun spawnDrain(state: DrainState): HANDLE? =
    memScoped {
        val ref = StableRef.create(state)
        val thread = alloc<HANDLEVar>()
        CreateThread(
            null,
            0u,
            staticCFunction(::staticDrainRoutine),
            ref.asCPointer(),
            0u,
            null,
        ).also { handle -> thread.value = handle }
        thread.value
    }

/**
 * The pump thread's spawn: the bytes feed the child's stdin's
 * pipe, the write's end closes for the child's EOF.
 */
private fun spawnPump(
    finished: kotlin.concurrent.AtomicInt,
    stdinWrite: HANDLE?,
    bytes: ByteArray,
): HANDLE? =
    memScoped {
        val ref = StableRef.create(PumpState(stdinWrite, bytes, finished))
        val thread = alloc<HANDLEVar>()
        CreateThread(
            null,
            0u,
            staticCFunction(::staticPumpRoutine),
            ref.asCPointer(),
            0u,
            null,
        ).also { handle -> thread.value = handle }
        thread.value
    }

/**
 * The drain's start routine: the C face of the Kotlin drain — the
 * stable-ref's unpack, the drain, the completion's count, the
 * ref's dispose.
 */
private fun staticDrainRoutine(arg: kotlinx.cinterop.CPointer<*>?): DWORD {
    val ref = arg!!.asStableRef<DrainState>()
    val state = ref.get()
    drainFd(state.read, state.face.byteSink, state.face.stream) { line -> state.face.append(line) }
    CloseHandle(state.read)
    state.finished.addAndGet(1)
    ref.dispose()
    return 0u
}

/**
 * The drains' spawn: the capture's threads (the merged pipe's one,
 * the separate pair's two; none under the raw capture — no pipes
 * exist) and the pumped stdin's. The raw capture's null answers
 * shape the join.
 */
private fun spawnDrains(
    pipes: PipeHandles,
    options: RunOptions,
    views: CaptureViews,
    merged: Boolean,
    raw: Boolean,
): DrainThreads {
    // the raw capture skips the capture drains (no pipes exist to
    // hear) — but the stdin's pump rides regardless: the bytes
    // feed, the write's end closes for the child's EOF. The pump's
    // absence held the pipe open forever, and findstr (the raw
    // face's own test, the run's hung lane) starved reading it
    val stdinThread =
        (options.stdin as? Stdin.From)?.let { from ->
            spawnPump(kotlin.concurrent.AtomicInt(0), pipes.stdin?.second, from.bytes)
        }
    if (raw) {
        return DrainThreads(null, null, stdinThread)
    }
    val finished = kotlin.concurrent.AtomicInt(0)
    val outThread =
        spawnDrain(
            DrainState(
                pipes.outRead,
                DrainFace(
                    views,
                    views.stdout,
                    options.byteSink,
                    if (merged) Stream.UNKNOWN else Stream.OUT,
                    options.sink,
                ),
                finished,
            ),
        )
    val errThread =
        if (merged) {
            null
        } else {
            spawnDrain(
                DrainState(
                    pipes.errRead,
                    DrainFace(
                        views,
                        views.stderr,
                        options.byteSink,
                        Stream.ERR,
                        options.sink,
                    ),
                    finished,
                ),
            )
        }
    return DrainThreads(outThread, errThread, stdinThread)
}

/**
 * The pump's start routine: the bytes feed the child's stdin's
 * pipe (the partial writes loop), the write's end closes for the
 * child's EOF.
 */
private fun staticPumpRoutine(arg: kotlinx.cinterop.CPointer<*>?): DWORD {
    val ref = arg!!.asStableRef<PumpState>()
    val state = ref.get()
    memScoped {
        val buffer = state.bytes
        var cursor = 0
        while (cursor < buffer.size) {
            val read = alloc<DWORDVar>()
            val ok =
                buffer.usePinned { pinned ->
                    WriteFile(
                        state.write,
                        pinned.addressOf(cursor),
                        (buffer.size - cursor).convert<DWORD>(),
                        read.ptr,
                        null,
                    )
                }
            if (ok == FALSE) {
                break // the child's gone: the feed's over
            }
            cursor += read.value.toInt()
        }
    }
    CloseHandle(state.write)
    state.finished.addAndGet(1)
    ref.dispose()
    return 0u
}

/**
 * One pipe's drain: reads to EOF, feeding the [ByteSink] the raw
 * chunks and whole lines to the [sink] (the partial tail carries
 * between reads).
 */
private fun drainFd(
    handle: HANDLE?,
    byteSink: ByteSink?,
    stream: Stream,
    sink: (String) -> Unit,
) {
    memScoped {
        val buffer = allocArray<ByteVar>(WinConstants.BUFFER_SIZE)
        val read = alloc<DWORDVar>()
        var carry = ""
        while (
            ReadFile(handle, buffer, WinConstants.BUFFER_SIZE.convert(), read.ptr, null) != FALSE &&
            read.value != 0u
        ) {
            // the raw face rides the copy: the decode/re-encode
            // round trip would corrupt a multi-byte sequence split
            // across the chunk's edge
            val raw = buffer.readBytes(read.value.toInt())
            byteSink?.accept(raw, stream)
            val chunk = carry + raw.decodeToString()
            val pieces = chunk.split('\n')
            carry = pieces.last()
            pieces.dropLast(1).forEach(sink)
        }
        if (carry.isNotEmpty()) {
            sink(carry)
        }
    }
}

/**
 * The command line: the words in the Microsoft quoting rules — a
 * word with whitespace (the newline faces included: the child's
 * CRT would split them unquoted) or quotes wears its own quotes;
 * the backslashes before a quote double and the quote wears its
 * own escape (`\"` — the old face left it bare and the word split
 * at every quote); a trailing backslash run doubles for the
 * closing quote's sake.
 */
private fun quote(command: List<String>): String =
    command.joinToString(" ") { word ->
        when {
            word.isEmpty() -> {
                "\"\""
            }

            word.none { it == ' ' || it == '\t' || it == '"' || it == '\n' || it == '\r' } -> {
                word
            }

            else -> {
                "\"" +
                    word.replace(
                        Regex("(\\\\*)(\"|$)"),
                    ) { match ->
                        match.groupValues[1] + match.groupValues[1] +
                            if (match.groupValues[2] == "\"") "\\\"" else ""
                    } + "\""
            }
        }
    }

/**
 * The session's mingw spawn: the same CreateProcessW's face, the
 * stdin always a pipe (the [RunOptions]' policy is not honored —
 * the forced `From` face wires the pipe and never auto-pumps: the
 * session's hand drives the write end), the drains riding Win32
 * threads from the spawn's own moment.
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
    return Session(MingwSessionDriver(executable, args, options, forced))
}

/**
 * The conversation's watchdog's packed state: the sleep's budget
 * (0: the destroyer's own face), the shared ruling's int, the
 * code this watchdog rules with.
 */
private class SessionWatchdogState(
    val hProcess: HANDLE?,
    val ms: Long,
    val ruling: kotlin.concurrent.AtomicInt,
    val reasonCode: Int,
)

/**
 * The conversation's watchdog's spawn: the fire-and-forget thread
 * (the handle's immediate close never terminates the thread — the
 * state rides the stable ref).
 */
private fun spawnSessionWatchdog(state: SessionWatchdogState): Unit =
    memScoped {
        val ref = StableRef.create(state)
        val thread =
            CreateThread(null, 0u, staticCFunction(::staticSessionWatchdogRoutine), ref.asCPointer(), 0u, null)
        if (thread != null && thread != INVALID_HANDLE_VALUE) {
            CloseHandle(thread)
        }
    }

private fun staticSessionWatchdogRoutine(arg: kotlinx.cinterop.CPointer<*>?): DWORD {
    val ref = arg!!.asStableRef<SessionWatchdogState>()
    val state = ref.get()
    Sleep(state.ms.convert<DWORD>())
    // the probe's verdict: still running → the ruling + the
    // TerminateProcess (the Windows' kill's only face); already
    // gone → its own exit speaks, not the boundary's hand. The
    // grace's wait lives in the caller's own waitFor — the
    // watchdog never touches the handle after the kill (the
    // use-after-close race's face: the waitFor's closes race this
    // thread's patience)
    if (WaitForSingleObject(state.hProcess, 0u) == WAIT_TIMEOUT.convert<DWORD>()) {
        state.ruling.compareAndSet(RULED_NONE, state.reasonCode)
        TerminateProcess(state.hProcess, 1u)
    }
    ref.dispose()
    return 0u
}

/**
 * The mingw conversation's machinery: the CreateProcessW-backed
 * spawn, the drains live from the spawn's moment, the stdin's
 * write end the driver's own hand, the timeout's and the
 * destroyer's watchdogs sharing the ruling.
 */
private class MingwSessionDriver(
    executable: String,
    args: List<String>,
    private val options: RunOptions,
    forced: RunOptions,
) : SessionDriver {
    private val merged = options.capture == CaptureMode.MERGED
    private val raw = options.capture == CaptureMode.NONE
    private val ruling = kotlin.concurrent.AtomicInt(RULED_NONE)
    private val spoken = kotlin.concurrent.AtomicInt(0)
    private val building = kotlin.concurrent.AtomicInt(0)
    private val views = CaptureViews()
    private val hProcess: HANDLE?
    private val hThread: HANDLE?
    private val stdinWrite: HANDLE?
    private val outDrain: HANDLE?
    private val errDrain: HANDLE?
    private val orphanJob: HANDLE?

    /** The child's pid (the Win32 process id). */
    override val pid: Long

    init {
        val pipes = createPipes(forced, merged, raw)

        // the orphan fence's job: the child's life rides the
        // parent's (the handle's close speaks the kill)
        orphanJob = if (options.killOnParentDeath) createOrphanJob() else null

        val process = spawnChild(executable, args, forced, pipes, orphanJob)
        hProcess = process.hProcess
        hThread = process.hThread
        pid = process.dwProcessId.toLong()

        // the parent's write-end copies close BEFORE the reading: a
        // leaked handle turns the drain into a deadlock; the
        // stdin's read end dies in the parent (the session keeps
        // the write end — the caller's hand)
        if (!raw) {
            CloseHandle(pipes.outWrite)
            if (!merged) {
                CloseHandle(pipes.errWrite)
            }
        }
        pipes.stdin?.let { CloseHandle(it.first) }
        stdinWrite = pipes.stdin?.second

        // the drains: the capture's threads (none under the raw
        // capture — no pipes exist to hear)
        outDrain =
            if (raw) {
                null
            } else {
                spawnDrain(
                    DrainState(
                        pipes.outRead,
                        DrainFace(
                            views,
                            views.stdout,
                            options.byteSink,
                            if (merged) Stream.UNKNOWN else Stream.OUT,
                            options.sink,
                        ),
                        kotlin.concurrent.AtomicInt(0),
                    ),
                )
            }
        errDrain =
            if (raw || merged) {
                null
            } else {
                spawnDrain(
                    DrainState(
                        pipes.errRead,
                        DrainFace(
                            views,
                            views.stderr,
                            options.byteSink,
                            Stream.ERR,
                            options.sink,
                        ),
                        kotlin.concurrent.AtomicInt(0),
                    ),
                )
            }

        // the timeout's watchdog: the same detached face the
        // destroyer rides, the vocabulary TIMEOUT
        options.timeout?.let {
            spawnSessionWatchdog(SessionWatchdogState(hProcess, it.inWholeMilliseconds, ruling, RULED_TIMEOUT))
        }
    }

    override fun write(bytes: ByteArray) {
        check(spoken.value == 0) {
            "the session's stdin already spoke its end"
        }
        var cursor = 0
        while (cursor < bytes.size) {
            memScoped {
                val written = alloc<DWORDVar>()
                bytes.usePinned { pinned ->
                    val ok =
                        WriteFile(
                            stdinWrite,
                            pinned.addressOf(cursor),
                            (bytes.size - cursor).convert<DWORD>(),
                            written.ptr,
                            null,
                        )
                    if (ok == FALSE) {
                        // the capture so far rides the exception
                        // (the promise's own face — the story the
                        // child told before the break)
                        throw ProcessException(
                            "the session's stdin broke (WriteFile failed, code ${GetLastError()})",
                            null,
                            views.lock.around { views.combined.toString() },
                        )
                    }
                }
                cursor += written.value.toInt()
            }
        }
    }

    override fun endOfInput() {
        check(spoken.addAndGet(1) == 1) {
            "the session's stdin already spoke its end"
        }
        CloseHandle(stdinWrite)
    }

    override fun destroy() {
        // the destroyer's own watchdog (ms=0): the probe rules only
        // a still-alive child; the already-exited child answers
        // nothing (every call spawns its patience — idempotent by
        // the ruling's own CAS)
        spawnSessionWatchdog(SessionWatchdogState(hProcess, 0, ruling, RULED_DESTROYED))
    }

    private var verdict: ProcessResult? = null

    override fun waitFor(): ProcessResult {
        // the single-shot face: the handles close here (the
        // process's, the threads', the fence's) — a second wait's
        // re-entry would re-close closed handles and re-read a
        // zeroed exit code, so the verdict caches and the re-ask
        // answers it; the CONCURRENT ask rides the building gate —
        // the first hand builds, the second's patience answers the
        // same verdict (the re-closed handle is the recycle's face)
        verdict?.let { return it }
        val built =
            if (building.compareAndSet(0, WinConstants.WAIT_BUILDING)) {
                // the conversation's faces reclaim: the unspoken
                // stdin's handle closes (the spoken end's own closed
                // in endOfInput — never a double face)
                if (spoken.value == 0) {
                    CloseHandle(stdinWrite)
                }
                // the drains' join: the handles wait, then close
                outDrain?.let { thread ->
                    WaitForSingleObject(thread, INFINITE)
                    CloseHandle(thread)
                }
                errDrain?.let { thread ->
                    WaitForSingleObject(thread, INFINITE)
                    CloseHandle(thread)
                }

                // the child's own wait: the watchdogs' kills have spoken or
                // the child's own exit speaks
                WaitForSingleObject(hProcess, INFINITE)
                val exitCode =
                    memScoped {
                        val code = alloc<DWORDVar>()
                        GetExitCodeProcess(hProcess, code.ptr)
                        code.value.toInt()
                    }
                CloseHandle(hProcess)
                CloseHandle(hThread)

                // the job's hygiene: the child's done (killed or exited) —
                // the fence's handle has nothing left to guard
                CloseHandle(orphanJob)
                val answer = verdictFor(views, exitCode, reasonOf(ruling.value), attributed = !merged && !raw)
                verdict = answer
                building.value = WinConstants.WAIT_BUILT
                answer
            } else {
                // the second hand's patience: the built face's
                // atomic orders the plain var's read (the
                // watchdog's own shape)
                while (building.value != WinConstants.WAIT_BUILT) {
                    Sleep(WinConstants.WAIT_TICK_MS)
                }
                null
            }
        return built ?: verdict!!
    }
}
