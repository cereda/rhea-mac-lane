// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The POSIX runner's smoke: the fork/execvp machinery against real
 * children — the argv's fidelity, the pipes' drains, the typed
 * verdicts.
 */
class RheaPosixSmokeTest {
    @Test
    fun `a wordy argv survives the exec`() {
        val result = Rhea.run("/bin/echo", "one word", "  spaced  ")
        assertIs<ProcessResult.Completed>(result)
        assertEquals("one word   spaced  ", result.output)
    }

    @Test
    fun `the separate capture attributes the streams`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo answered; echo complained >&2"),
                options = RunOptions(capture = CaptureMode.SEPARATE),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals("answered", result.stdout?.trimEnd())
        assertEquals("complained", result.stderr?.trimEnd())
    }

    @Test
    fun `the merged capture keeps the tool's own interleaving`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo one; echo two >&2; echo three"),
                options = RunOptions(capture = CaptureMode.MERGED),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals("one\ntwo\nthree", result.output)
    }

    @Test
    fun `a missing command is the boundary's failure`() {
        assertFailsWith<ProcessException> {
            Rhea.run("/no/such/command/anywhere")
        }
    }

    @Test
    fun `a chatty child does not deadlock the drains`() {
        // the pipe buffers are ~64KiB: a child writing past them
        // blocks unless the drains run concurrently — the poll loop
        // answers the question
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "yes rhea | head -c 200000 > /dev/stdout; echo done >&2"),
            )
        assertIs<ProcessResult.Completed>(result)
        assertTrue(result.output.contains("done"))
    }
}
