// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.CreateThread
import platform.windows.DWORD
import platform.windows.DWORDVar
import platform.windows.FALSE
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.TRUE
import platform.windows.TerminateProcess
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile
import kotlin.time.Duration

/** The POSIX-... the Windows boundary's fixed numbers. */
private object WinConstants {
    // the buffer a read rides in
    const val BUFFER_SIZE = 8_192

    // the destroy escalation's grace: TerminateProcess is the
    // kill's only face (no graceful signal exists), so the grace
    // is the child's own cleanup's chance
    const val DESTROY_GRACE_MS = 500u
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
 * The drain thread's packed state: the stable-ref's payload (the
 * Win32's start routine speaks one pointer).
 */
private class DrainState(
    val read: HANDLE?,
    val combined: StringBuilder,
    val attributed: StringBuilder,
    val stream: Stream,
    val sink: Sink?,
    val finished: kotlin.concurrent.AtomicInt,
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
 * verdict speaks `Destroyed`.
 */
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult =
    memScoped {
        val merged = options.capture == CaptureMode.MERGED

        // the pipes': the handles' phase
        val pipes = createPipes(options, merged)

        // the spawn's phase: the child's process, the stdio's faces
        val process = spawnChild(executable, args, options, pipes, merged)

        // the parent's write-end copies close BEFORE the reading: a
        // leaked handle turns the drain into a deadlock
        CloseHandle(pipes.outWrite)
        if (!merged) {
            CloseHandle(pipes.errWrite)
        }
        pipes.stdin?.let { CloseHandle(it.first) }

        // the drains: the concurrent Win32 threads — the merged
        // pipe's one drain; the separate pair's two, each into its
        // buffer with the sink hearing every line attributed; plus
        // the pumped stdin's thread (the bytes feed, the write's
        // end closes for the child's EOF)
        val finished = kotlin.concurrent.AtomicInt(0)
        val combined = StringBuilder()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread =
            spawnDrain(DrainState(pipes.outRead, combined, stdout, Stream.OUT, options.sink, finished))
        val errThread =
            if (merged) {
                null
            } else {
                spawnDrain(DrainState(pipes.errRead, combined, stderr, Stream.ERR, options.sink, finished))
            }
        val stdinThread =
            (options.stdin as? Stdin.From)?.let { from ->
                spawnPump(finished, pipes.stdin?.second, from.bytes)
            }

        // the wait's budget: the expiry speaks TerminateProcess (the
        // Windows' kill's only face)
        val timedOut = waitForChild(process.hProcess, options.timeout)

        // the join's answer: the threads' handles wait, then close
        WaitForSingleObject(outThread, INFINITE)
        CloseHandle(outThread)
        errThread?.let { thread ->
            WaitForSingleObject(thread, INFINITE)
            CloseHandle(thread)
        }
        stdinThread?.let { thread ->
            WaitForSingleObject(thread, INFINITE)
            CloseHandle(thread)
        }

        val exitCode = alloc<DWORDVar>()
        GetExitCodeProcess(process.hProcess, exitCode.ptr)
        CloseHandle(process.hProcess)
        CloseHandle(process.hThread)

        when {
            timedOut -> {
                ProcessResult.Destroyed(combined.toString().trimEnd('\n'), DestroyReason.TIMEOUT)
            }

            else -> {
                ProcessResult.Completed(
                    exitCode.value.toInt(),
                    combined.toString().trimEnd('\n'),
                    stdout.toString().trimEnd('\n').takeUnless { merged },
                    stderr.toString().trimEnd('\n').takeUnless { merged },
                )
            }
        }
    }

/**
 * The pipes'-phase: the out/err pair (one under MERGED — the
 * kernel's own fold) + the stdin's pipe when the run pumps it.
 */
private fun createPipes(
    options: RunOptions,
    merged: Boolean,
): PipeHandles =
    memScoped {
        val security =
            alloc<SECURITY_ATTRIBUTES>().apply {
                nLength = sizeOf<SECURITY_ATTRIBUTES>().convert<DWORD>()
                bInheritHandle = TRUE
            }
        val outRead = alloc<HANDLEVar>()
        val outWrite = alloc<HANDLEVar>()
        if (CreatePipe(outRead.ptr, outWrite.ptr, security.ptr, 0u) == FALSE) {
            throw ProcessException("CreatePipe failed (code ${GetLastError()})")
        }
        val errRead = alloc<HANDLEVar>()
        val errWrite = alloc<HANDLEVar>()
        if (!merged && CreatePipe(errRead.ptr, errWrite.ptr, security.ptr, 0u) == FALSE) {
            throw ProcessException("CreatePipe failed (code ${GetLastError()})")
        }
        val stdinRead = alloc<HANDLEVar>()
        val stdinWrite = alloc<HANDLEVar>()
        val pumpsStdin = options.stdin is Stdin.From
        if (pumpsStdin && CreatePipe(stdinRead.ptr, stdinWrite.ptr, security.ptr, 0u) == FALSE) {
            throw ProcessException("CreatePipe failed (code ${GetLastError()})")
        }
        PipeHandles(
            outRead.value,
            outWrite.value,
            errRead.value,
            errWrite.value,
            stdinRead.value to stdinWrite.value,
            merged,
        )
    }

/**
 * The spawn's phase: the child's process, the stdio's faces (the
 * pipes' read ends; the stdin's face = the pumped pipe's read end
 * or the null device's silence).
 */
private fun spawnChild(
    executable: String,
    args: List<String>,
    options: RunOptions,
    pipes: PipeHandles,
    merged: Boolean,
): PROCESS_INFORMATION =
    memScoped {
        val startup =
            alloc<STARTUPINFOW>().apply {
                cb = sizeOf<STARTUPINFOW>().convert<DWORD>()
                dwFlags = STARTF_USESTDHANDLES.convert<DWORD>()
                hStdInput =
                    when (options.stdin) {
                        Stdin.Inherit -> INVALID_HANDLE_VALUE
                        Stdin.Closed -> INVALID_HANDLE_VALUE
                        is Stdin.From -> pipes.stdin?.first
                    }
                hStdOutput = pipes.outWrite
                hStdError = if (merged) pipes.outWrite else pipes.errWrite
            }
        val process = alloc<PROCESS_INFORMATION>()
        val commandLine = quote(listOf(executable) + args).wcstr.ptr
        val created =
            CreateProcessW(
                null,
                commandLine,
                null,
                null,
                TRUE,
                0u,
                null,
                null,
                startup.ptr,
                process.ptr,
            )
        if (created == FALSE) {
            throw ProcessException(
                "CreateProcessW failed for $executable (code ${GetLastError()})",
            )
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
    drainFd(state.read) { line ->
        state.combined.appendLine(line)
        state.attributed.appendLine(line)
        state.sink?.accept(line, state.stream)
    }
    CloseHandle(state.read)
    state.finished.addAndGet(1)
    ref.dispose()
    return 0u
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
 * One pipe's drain: reads to EOF, feeding whole lines to the
 * [sink] (the partial tail carries between reads).
 */
private fun drainFd(
    handle: HANDLE?,
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
            val chunk = carry + buffer.readBytes(read.value.toInt()).decodeToString()
            val lines = chunk.split('\n')
            carry = lines.last()
            lines.dropLast(1).forEach(sink)
        }
        if (carry.isNotEmpty()) {
            sink(carry)
        }
    }
}

/**
 * The command line: the words in the Microsoft quoting rules — a
 * word with whitespace or quotes wears its own quotes, and every
 * backslash run before a quote or the line's end doubles up.
 */
private fun quote(command: List<String>): String =
    command.joinToString(" ") { word ->
        when {
            word.isEmpty() -> {
                "\"\""
            }

            word.none { it == ' ' || it == '\t' || it == '"' } -> {
                word
            }

            else -> {
                "\"" +
                    word.replace(
                        Regex("(\\\\*)(\"|$)"),
                    ) { match ->
                        match.groupValues[1] + match.groupValues[1] + match.groupValues[2]
                    } + "\""
            }
        }
    }
