// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import platform.windows.Sleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The break face's pace: the child's echo long done, the pipe's dead. */
private const val PIPE_BREAK_PACE_MS = 2000u

/**
 * The broken pipe's face on the Windows tier: the child's exit is
 * the read end's death, the parent's write answers the boundary's
 * own vocabulary (the POSIX's and the JVM's own tests speak the
 * same face).
 */
class RheaWindowsPipeBreakTest {
    @Test
    fun `the session's write answers the broken pipe`() {
        val session = Rhea.start("cmd.exe", "/c", "echo gone")
        Sleep(PIPE_BREAK_PACE_MS)
        assertFailsWith<ProcessException> { session.write("into the void\n") }
        session.destroy()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
    }
}
