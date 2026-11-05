// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fork
import platform.posix.open
import platform.posix.pipe
import platform.posix.poll
import platform.posix.read
import platform.posix.size_t
import platform.posix.strerror
import platform.posix.waitpid

// the constants the boundary speaks: the buffer a read rides in,
// the exec failure's exit status (the shell convention), and the
// wait status factorization's masks (the low 7 bits name the
// signal, the high byte holds the exit code)
private const val BUFFER_SIZE = 8_192
private const val EXIT_COULD_NOT_EXECUTE = 127
private const val WSTATUS_MASK = 0x7f
private const val WSTATUS_EXIT_SHIFT = 8
private const val WSTATUS_EXIT_MASK = 0xff

/**
 * The POSIX actual: `fork` + `execvp` (the argv speaks directly to
 * the kernel — no shell, no quoting) with the child's streams
 * riding pipes, the parent draining and reaping. The child's exec
 * failure exits 127, the shell's own convention; the wait status
 * decodes through the wait macros' factorization (the low byte
 * zero: exited; otherwise the low 7 bits name the signal).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult =
    memScoped {
        // the pipes: MERGED wires the child's stdout and stderr into
        // ONE pipe (the kernel's own interleaving); SEPARATE gives each
        // stream its own pipe and drains them through a single poll
        // loop — the concurrent drain that never deadlocks
        val merged = options.capture == CaptureMode.MERGED
        val outPipe = allocArray<IntVar>(2)
        val errPipe = allocArray<IntVar>(2)
        if (pipe(outPipe) != 0 || (!merged && pipe(errPipe) != 0)) {
            throw ProcessException("pipe failed: ${strerror(errno)?.toKString()}")
        }
        val wiring = PipeWiring(outPipe, errPipe, merged)

        val pid = fork()
        if (pid < 0) {
            throw ProcessException("fork failed: ${strerror(errno)?.toKString()}")
        }
        if (pid == 0) {
            childExecutes(executable, args, options, wiring)
        }

        // the parent's side: the write ends close (the drains see EOF
        // when the child exits), the read ends drain
        close(wiring.outWrite)
        if (!merged) {
            close(wiring.errWrite)
        }

        val combined = StringBuilder()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        if (merged) {
            drainFd(wiring.outRead) { line -> combined.appendLine(line) }
            close(wiring.outRead)
        } else {
            // one thread, two fds: poll blocks until a pipe has bytes,
            // the rounds append into the combined buffer in the true
            // arrival order (the single-threaded loop serializes them)
            drainTwo(wiring.outRead, wiring.errRead, combined, stdout, stderr)
            close(wiring.outRead)
            close(wiring.errRead)
        }

        val verdict = reap(pid)
        ProcessResult.Completed(
            verdict,
            combined.toString().trimEnd('\n'),
            stdout.toString().trimEnd('\n').takeUnless { merged },
            stderr.toString().trimEnd('\n').takeUnless { merged },
        )
    }

/**
 * The two pipes' descriptor ends, as the child and the parent's
 * sides speak them.
 */
private class PipeWiring(
    outPipe: CArrayPointer<IntVar>,
    errPipe: CArrayPointer<IntVar>,
    val merged: Boolean,
) {
    val outRead = outPipe[0]
    val outWrite = outPipe[1]
    val errRead = errPipe[0]
    val errWrite = errPipe[1]
}

/**
 * The child: the pipes' write ends become the child's streams
 * (both into one under MERGED — the kernel's own fold), the stdin
 * policy speaks, both pipe ends leave the child's table, then the
 * argv executes — the exec failure exits 127 (execvp returns only
 * on failure).
 */
private fun childExecutes(
    executable: String,
    args: List<String>,
    options: RunOptions,
    wiring: PipeWiring,
): Nothing {
    when (options.stdin) {
        StdinPolicy.INHERIT -> {
            // the child inherits the parent's stdin: nothing to do
        }

        // the null device: an immediate end of stream
        StdinPolicy.CLOSED -> {
            val nullFd = open("/dev/null", 0)
            dup2(nullFd, STDIN_FILENO)
            close(nullFd)
        }
    }
    if (wiring.merged) {
        dup2(wiring.outWrite, STDOUT_FILENO)
        dup2(wiring.outWrite, 2) // the stderr joins the same pipe
    } else {
        dup2(wiring.outWrite, STDOUT_FILENO)
        dup2(wiring.errWrite, 2)
    }
    close(wiring.outRead)
    close(wiring.outWrite)
    if (!wiring.merged) {
        close(wiring.errRead)
        close(wiring.errWrite)
    }
    memScoped {
        val argv = allocArray<kotlinx.cinterop.CPointerVar<ByteVar>>(args.size + 2)
        (listOf(executable) + args).forEachIndexed { index, word ->
            argv[index] = word.cstr.ptr
        }
        argv[args.size + 1] = null
        execvp(executable, argv)
    }
    _exit(EXIT_COULD_NOT_EXECUTE)
    // the exec's failure exits above; this line never runs, but
    // the compiler's flow analysis wants the Nothing spelled
    error("unreachable")
}

/**
 * The single-fd drain: reads to EOF, feeding whole lines to the
 * [sink].
 */
@OptIn(ExperimentalForeignApi::class)
private fun drainFd(
    fd: Int,
    sink: (String) -> Unit,
) {
    val buffer = ByteArray(BUFFER_SIZE)
    var carry = ""
    while (true) {
        val count = read(fd, buffer.refTo(0), BUFFER_SIZE.convert<size_t>())
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
 * The two-fd drain: poll BLOCKS until a pipe has bytes (no spin),
 * the rounds append into the combined buffer in the true arrival
 * order while the attributed buffers keep the streams apart.
 */
@OptIn(ExperimentalForeignApi::class)
private fun drainTwo(
    outFd: Int,
    errFd: Int,
    combined: StringBuilder,
    stdout: StringBuilder,
    stderr: StringBuilder,
) {
    memScoped {
        val fds = allocArray<platform.posix.pollfd>(2)
        fds[0].fd = outFd
        fds[0].events = platform.posix.POLL_IN.toShort()
        fds[1].fd = errFd
        fds[1].events = platform.posix.POLL_IN.toShort()

        val outCarry = arrayOf("")
        val errCarry = arrayOf("")
        var open = 2
        while (open > 0) {
            // the blocking poll: the kernel parks the thread until
            // an event; no spin, no idle CPU
            if (poll(fds, 2.convert(), -1) < 0) {
                throw ProcessException("poll failed: ${strerror(errno)?.toKString()}")
            }
            if (fds[0].revents.toInt() != 0) {
                if (!drainChunk(outFd, outCarry) { line ->
                        combined.appendLine(line)
                        stdout.appendLine(line)
                    }
                ) {
                    open--
                    fds[0].fd = -1
                }
            }
            if (fds[1].revents.toInt() != 0) {
                if (!drainChunk(errFd, errCarry) { line ->
                        combined.appendLine(line)
                        stderr.appendLine(line)
                    }
                ) {
                    open--
                    fds[1].fd = -1
                }
            }
        }
    }
}

/**
 * One poll-answered chunk from an fd: the bytes decode into whole
 * lines for the [sink], the partial tail carries over.
 * @return whether the fd still lives (false: EOF — the child's
 * end closed).
 */
@OptIn(ExperimentalForeignApi::class)
private fun drainChunk(
    fd: Int,
    carry: Array<String>,
    sink: (String) -> Unit,
): Boolean {
    val buffer = ByteArray(BUFFER_SIZE)
    val count = read(fd, buffer.refTo(0), BUFFER_SIZE.convert<size_t>())
    if (count <= 0L) {
        if (carry[0].isNotEmpty()) {
            sink(carry[0])
            carry[0] = ""
        }
        return false
    }
    val chunk = carry[0] + buffer.decodeToString(0, count.toInt())
    val lines = chunk.split('\n')
    carry[0] = lines.last()
    lines.dropLast(1).forEach(sink)
    return true
}

/**
 * The reaping: the wait status decodes through the factorization
 * — the low byte zero is a clean exit (whose code speaks the exec
 * failure when it is 127), anything else died by the signal in
 * the low 7 bits.
 */
@OptIn(ExperimentalForeignApi::class)
private fun reap(pid: Int): Int {
    memScoped {
        val wstatus = alloc<IntVar>()
        if (waitpid(pid, wstatus.ptr, 0) < 0) {
            throw ProcessException("waitpid failed: ${strerror(errno)?.toKString()}")
        }
        failureOf(wstatus.value)?.let { throw ProcessException(it) }
        return (wstatus.value shr WSTATUS_EXIT_SHIFT) and WSTATUS_EXIT_MASK
    }
}

/**
 * The wait status's verdict: null for a healthy exit, the failure's
 * story otherwise (a signalled child, or the exec failure's own 127
 * exit).
 */
private fun failureOf(status: Int): String? =
    when (val raw = status and WSTATUS_MASK) {
        0 -> {
            when ((status shr WSTATUS_EXIT_SHIFT) and WSTATUS_EXIT_MASK) {
                EXIT_COULD_NOT_EXECUTE -> {
                    "the command could not execute (is it in the system path?)"
                }

                else -> {
                    null
                }
            }
        }

        else -> {
            "the command died from signal $raw"
        }
    }
