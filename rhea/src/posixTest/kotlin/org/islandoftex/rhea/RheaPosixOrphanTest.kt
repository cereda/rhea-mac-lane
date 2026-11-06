// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SIGKILL
import platform.posix._exit
import platform.posix.close
import platform.posix.fork
import platform.posix.kill
import platform.posix.nanosleep
import platform.posix.open
import platform.posix.read
import platform.posix.timespec
import platform.posix.unlink
import platform.posix.waitpid
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The orphan fence's end-to-end proof: the test forks a child,
 * the child starts a fenced rhea session and exits — the
 * grandchild's fate answers the fence. The macOS tier rides this
 * file as the documented no-op's silence (the fenced face would
 * fail here; the platform offers no kernel face).
 */
internal const val FENCED_PID_FILE = "/tmp/rhea-orphan-fenced.pid"
private const val UNFENCED_PID_FILE = "/tmp/rhea-orphan-unfenced.pid"

/** The fence's numbers: the mode's face, the buffer, the probe's currency. */
private const val PID_FILE_MODE = 420 // 0644
private const val PID_BUFFER_SIZE = 32
internal const val FENCE_PROBE_SPINS = 20
internal const val FENCE_PROBE_TICK_MS = 100L
private const val FENCE_LATENCY_MS = 500L
private const val MS_PER_SECOND = 1_000L
private const val NS_PER_MS = 1_000_000L

/** The grandchild pid's ride: the forked child records, the test reads. */
internal fun writePid(
    path: String,
    pid: Long,
) {
    val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, PID_FILE_MODE)
    assertNotEquals(-1, fd)
    val bytes = pid.toString().encodeToByteArray()
    assertEquals(bytes.size.toLong(), write(fd, bytes.refTo(0), bytes.size.convert()))
    close(fd)
}

internal fun readPid(path: String): Long {
    val fd = open(path, O_RDONLY)
    assertNotEquals(-1, fd)
    val buffer = ByteArray(PID_BUFFER_SIZE)
    val count = read(fd, buffer.refTo(0), buffer.size.convert())
    close(fd)
    return buffer.decodeToString(0, count.toInt()).trim().toLong()
}

internal fun sleepMs(ms: Long) {
    memScoped {
        val spec = alloc<timespec>()
        spec.tv_sec = ms / MS_PER_SECOND
        spec.tv_nsec = (ms % MS_PER_SECOND) * NS_PER_MS
        nanosleep(spec.ptr, null)
    }
}

class RheaPosixOrphanTest {
    @Test
    fun `the unfenced orphan survives its parent`() {
        unlink(UNFENCED_PID_FILE)
        val forked = fork()
        assertNotEquals(-1, forked)
        if (forked == 0) {
            // the daemon's door: no fence — the grandchild outlives
            // the child's death (the documented behavior)
            val session =
                Rhea.start("/bin/sleep", "60", options = RunOptions(killOnParentDeath = false))
            writePid(UNFENCED_PID_FILE, session.pid)
            _exit(0)
        }
        memScoped {
            val wstatus = alloc<IntVar>()
            assertEquals(forked, waitpid(forked, wstatus.ptr, 0))
        }
        val grand = readPid(UNFENCED_PID_FILE)
        sleepMs(FENCE_LATENCY_MS)
        assertEquals(0, kill(grand.toInt(), 0), "the unfenced grandchild should outlive")
        // the cleanup: no orphans left in the CI's house
        kill(grand.toInt(), SIGKILL)
        unlink(UNFENCED_PID_FILE)
    }
}
