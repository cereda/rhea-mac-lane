// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.execvp
import platform.posix.fork
import platform.posix.pipe
import platform.posix.read
import platform.posix.size_t
import platform.posix.waitpid
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bare probes: the raw fork/pipe/dup2/exec dance, one layer
 * per test — the machinery's truth without a single line of the
 * Rhea's own code. The small units' doctrine: each layer's
 * verdict is a checkpoint.
 */
class BareProbeTest {
    /**
     * Unit 1: the fork's + the stdin's pipe's + the dup2's + the
     * exec's + the parent's write's + the wait's — the cat's read
     * of the pumped bytes, the raw kernel's dance.
     */
    @Test
    fun `unit 1 - the raw pipe write feeds a forked cat`() =
        memScoped {
            val stdinPipe = allocArray<IntVar>(PIPE_PAIR)
            assertEquals(0, pipe(stdinPipe), "pipe must build")

            val pid = fork()
            assertTrue(pid >= 0, "fork must not fail")
            if (pid == 0) {
                // the child: the stdin's pipe's read end becomes fd 0;
                // BOTH pipe ends close in the child's table (the read's
                // dup2'd to 0, the write's = the child must NOT hold
                // it — the EOF's source!)
                dup2(stdinPipe[0], STDIN_FILENO)
                close(stdinPipe[0])
                close(stdinPipe[1])
                val argv = allocArray<ByteVarPointer>(ECHO_ARGV)
                argv[0] = "/bin/cat".cstr.ptr
                argv[1] = null
                execvp("/bin/cat", argv)
                _exit(EXIT_COULD_NOT_EXECUTE)
            }

            // the parent: the read's end closes (the child's own!), the
            // bytes feed, the write's end closes for the child's EOF
            close(stdinPipe[0])
            val bytes = "fed by rhea\n".encodeToByteArray()
            var written = 0
            while (written < bytes.size) {
                val n = write(stdinPipe[1], bytes.refTo(written), (bytes.size - written).convert<size_t>())
                assertTrue(n > 0, "write must progress")
                written += n.toInt()
            }
            close(stdinPipe[1])

            // the drain: the child's answer rides a fresh pipe? no —
            // the unit's simplest: the child's stdout = the INHERITED
            // (the test's own console); the wait's the checkpoint
            val wstatus = alloc<IntVar>()
            val wait = waitpid(pid, wstatus.ptr, 0)
            assertEquals(pid, wait, "the child must reap")
            val code = (wstatus.value shr WSTATUS_EXIT_SHIFT) and WSTATUS_EXIT_MASK
            assertEquals(0, code, "cat must exit clean: the bytes flowed, the EOF arrived")
        }

    /**
     * Unit 2: the read's side — the forked child's stdout rides a
     * pipe; the parent drains it to EOF (the child's death's
     * guaranteed EOF).
     */
    @Test
    fun `unit 2 - the parent drains the child's stdout pipe to EOF`() =
        memScoped {
            val outPipe = allocArray<IntVar>(PIPE_PAIR)
            assertEquals(0, pipe(outPipe), "pipe must build")

            val pid = fork()
            assertTrue(pid >= 0, "fork must not fail")
            if (pid == 0) {
                dup2(outPipe[1], STDOUT_FILENO)
                close(outPipe[0])
                close(outPipe[1])
                val argv = allocArray<ByteVarPointer>(ECHO_ARGV)
                argv[0] = "/bin/echo".cstr.ptr
                argv[1] = "the drain's answer".cstr.ptr
                argv[2] = null
                execvp("/bin/echo", argv)
                _exit(EXIT_COULD_NOT_EXECUTE)
            }

            // the parent: the write's end closes FIRST (the EOF's
            // source!), then the read drains to EOF
            close(outPipe[1])
            val buffer = ByteArray(READ_BUFFER)
            val collected = StringBuilder()
            while (true) {
                val n = read(outPipe[0], buffer.refTo(0), READ_BUFFER.convert<size_t>())
                if (n <= READ_NO_DATA) break
                collected.append(buffer.decodeToString(0, n.toInt()))
            }
            close(outPipe[0])

            val wstatus = alloc<IntVar>()
            waitpid(pid, wstatus.ptr, 0)
            assertEquals(0, (wstatus.value shr WSTATUS_EXIT_SHIFT) and WSTATUS_EXIT_MASK)
            assertEquals("the drain's answer\n", collected.toString())
        }
}

/** The argv's C-string pointer's alias (the read's brevity's sake). */
private typealias ByteVarPointer = kotlinx.cinterop.CPointerVar<ByteVar>

// the probe's own geometry: the pipe's pair's fd-count, the cat's
// argv's slot count, and the wait-status's decode's masks (the
// same factorization the library speaks)
private const val PIPE_PAIR = 2
private const val WSTATUS_EXIT_SHIFT = 8
private const val WSTATUS_EXIT_MASK = 0xff
private const val EXIT_COULD_NOT_EXECUTE = 127
private const val ECHO_ARGV = 4
private const val READ_BUFFER = 256
private const val READ_NO_DATA = 0L
