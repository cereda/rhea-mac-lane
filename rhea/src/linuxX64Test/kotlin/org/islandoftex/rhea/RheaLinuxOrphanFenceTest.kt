// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix._exit
import platform.posix.fork
import platform.posix.kill
import platform.posix.unlink
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The orphan fence's Linux-only proof: `PR_SET_PDEATHSIG` is the
 * kernel's own face (the macOS tier answers nothing — its
 * unfenced face lives in the posixTest's own probe file). The
 * fork-based dance: the test forks a child, the child starts a
 * fenced rhea session and exits — the grandchild's fate answers
 * the fence.
 */
private const val FENCED_PID_FILE = "/tmp/rhea-orphan-fenced.pid"
private const val FENCE_PROBE_SPINS = 20
private const val FENCE_PROBE_TICK_MS = 100L

class RheaLinuxOrphanFenceTest {
    @Test
    fun `the fenced orphan dies with its parent`() {
        unlink(FENCED_PID_FILE)
        val forked = fork()
        assertNotEquals(-1, forked)
        if (forked == 0) {
            // the child's face: a fenced session, the grandchild's
            // pid home, the exit — the fence watches this thread
            val session = Rhea.start("/bin/sleep", "60")
            writePid(FENCED_PID_FILE, session.pid)
            _exit(0)
        }
        memScoped {
            val wstatus = alloc<IntVar>()
            assertEquals(forked, waitpid(forked, wstatus.ptr, 0))
        }
        val grand = readPid(FENCED_PID_FILE)
        // the fence's proof: the kernel's SIGKILL lands the moment
        // the parent's thread died — the probe answers ESRCH fast
        var alive = 0
        var spins = 0
        while (spins < FENCE_PROBE_SPINS) {
            alive = kill(grand.toInt(), 0)
            if (alive != 0) {
                break
            }
            sleepMs(FENCE_PROBE_TICK_MS)
            spins++
        }
        unlink(FENCED_PID_FILE)
        assertEquals(-1, alive, "the grandchild survived the parent's death")
    }
}
