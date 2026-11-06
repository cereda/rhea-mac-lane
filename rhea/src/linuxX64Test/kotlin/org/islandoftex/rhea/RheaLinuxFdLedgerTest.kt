// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fd ledger on the Linux tier: the drains' own hygiene, the
 * child's inherited view of `/proc/self/fd` — two counting runs
 * answer the same number when the parent leaks nothing (the
 * mingw's handle ledger's mirror; the POSIX tier's drains close
 * their read ends now, the leak's own proof).
 */
class RheaLinuxFdLedgerTest {
    @Test
    fun `a captured run leaves no fd behind`() {
        // the counting child inherits the parent's every fd (the
        // pipes carry no CLOEXEC) — the parent's leak rides the
        // count: two runs, one answer
        fun count(): String =
            (Rhea.run("/bin/sh", "-c", "ls /proc/self/fd | wc -l") as ProcessResult.Completed)
                .output
                .trim()

        count() // the warmup: the boot's lazy faces armed
        val first = count()
        val second = count()
        assertEquals(first, second, "the fd's face: $first then $second")
    }
}
