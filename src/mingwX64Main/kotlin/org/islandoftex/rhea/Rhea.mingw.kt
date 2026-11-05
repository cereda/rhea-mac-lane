// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
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
import platform.windows.WaitForSingleObject

// the buffer a read rides in: a kpsewhich-class answer is a
// handful of lines
private const val BUFFER_SIZE = 8_192

/**
 * The Win32 actual: `CreateProcessW` with the child's streams
 * riding anonymous pipes. Windows carries the argv as one command
 * line, so the words wear the Microsoft quoting rules (spaces
 * quoted, backslashes before quotes doubled). MERGED wires the
 * child's stdout and stderr into one pipe (the kernel's own
 * fold); SEPARATE gives each stream its pipe and drains in turn.
 */
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult =
    memScoped {
        val merged = options.capture == CaptureMode.MERGED

        // the pipe(s): the handles inherit into the child; the
        // separate mode gives the stderr its own pipe
        val outRead = alloc<HANDLEVar>()
        val outWrite = alloc<HANDLEVar>()
        val errRead = alloc<HANDLEVar>()
        val errWrite = alloc<HANDLEVar>()
        createPipes(merged, outRead, outWrite, errRead, errWrite)

        // only the child's streams ride the pipes; stdin speaks its
        // policy (the parent's own, or the null device's silence)
        val startup =
            alloc<STARTUPINFOW>().apply {
                cb = sizeOf<STARTUPINFOW>().convert<DWORD>()
                dwFlags = STARTF_USESTDHANDLES.convert<DWORD>()
                hStdInput = INVALID_HANDLE_VALUE
                hStdOutput = outWrite.value
                hStdError = if (merged) outWrite.value else errWrite.value
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
        // the parent's write-end copies close BEFORE the reading: a
        // leaked handle turns the drain into a deadlock
        CloseHandle(outWrite.value)
        if (!merged) {
            CloseHandle(errWrite.value)
        }
        if (created == FALSE) {
            CloseHandle(outRead.value)
            if (!merged) {
                CloseHandle(errRead.value)
            }
            throw ProcessException(
                "CreateProcessW failed for $executable (code ${GetLastError()})",
            )
        }

        // the drains and the reaping: the merged pipe reads to EOF;
        // the separate pair reads in turn (the pipe buffers carry a
        // chatty child's burst)
        val captures = captureAndReap(process.hProcess, outRead.value, errRead.value, merged)
        CloseHandle(process.hProcess)
        CloseHandle(process.hThread)

        ProcessResult.Completed(
            captures.exitCode,
            captures.combined.trimEnd('\n'),
            captures.stdout.trimEnd('\n').takeUnless { merged },
            captures.stderr.trimEnd('\n').takeUnless { merged },
        )
    }

/**
 * The drains and the reaping: the merged pipe reads to EOF; the
 * separate pair reads in turn (the wait rides between them: the
 * child's exit closes its ends, the second drain sees EOF
 * cleanly). The exit code answers after the wait.
 */
private class Captures(
    val exitCode: Int,
    val combined: String,
    val stdout: String,
    val stderr: String,
)

private fun captureAndReap(
    hProcess: HANDLE?,
    outRead: HANDLE?,
    errRead: HANDLE?,
    merged: Boolean,
): Captures {
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val combined = StringBuilder()
    drainFd(outRead) { line ->
        combined.appendLine(line)
        stdout.appendLine(line)
    }
    CloseHandle(outRead)
    if (!merged) {
        WaitForSingleObject(hProcess, INFINITE)
        drainFd(errRead) { line ->
            combined.appendLine(line)
            stderr.appendLine(line)
        }
        CloseHandle(errRead)
    }
    WaitForSingleObject(hProcess, INFINITE)
    memScoped {
        val exitCode = alloc<DWORDVar>()
        GetExitCodeProcess(hProcess, exitCode.ptr)
        return Captures(
            exitCode.value.toInt(),
            combined.toString(),
            stdout.toString(),
            stderr.toString(),
        )
    }
}

/**
 * The pipes: the handles inherit into the child; the separate mode
 * gives the stderr its own pipe.
 */
private fun createPipes(
    merged: Boolean,
    outRead: HANDLEVar,
    outWrite: HANDLEVar,
    errRead: HANDLEVar,
    errWrite: HANDLEVar,
) {
    memScoped {
        val security =
            alloc<SECURITY_ATTRIBUTES>().apply {
                nLength = sizeOf<SECURITY_ATTRIBUTES>().convert<DWORD>()
                bInheritHandle = TRUE
            }
        if (CreatePipe(outRead.ptr, outWrite.ptr, security.ptr, 0u) == FALSE) {
            throw ProcessException("CreatePipe failed (code ${GetLastError()})")
        }
        if (!merged && CreatePipe(errRead.ptr, errWrite.ptr, security.ptr, 0u) == FALSE) {
            throw ProcessException("CreatePipe failed (code ${GetLastError()})")
        }
    }
}

/**
 * One pipe's drain: reads to EOF, feeding whole lines to the
 * [sink] (the partial tail carries between reads).
 */
private fun drainFd(
    handle: HANDLE?,
    sink: (String) -> Unit,
) {
    // a null handle never answers: the drain is a polite no-op
    if (handle == null) return
    memScoped {
        val buffer = allocArray<ByteVar>(BUFFER_SIZE)
        val read = alloc<DWORDVar>()
        var carry = ""
        while (
            ReadFile(handle, buffer, BUFFER_SIZE.convert(), read.ptr, null) != FALSE &&
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
