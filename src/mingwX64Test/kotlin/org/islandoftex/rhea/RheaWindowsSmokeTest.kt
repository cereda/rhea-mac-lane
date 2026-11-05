// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Win32 runner's smoke: the CreateProcessW machinery against
 * real children — the quoting rules, the pipes, the typed
 * verdicts. `hostname` and `cmd.exe` ship with every Windows.
 */
class RheaWindowsSmokeTest {
    @Test
    fun `a child runs and its verdict carries the exit code`() {
        val result = Rhea.run("cmd.exe", "/c", "echo hello rhea")
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("hello rhea"))
    }

    @Test
    fun `a wordy argv quotes through`() {
        val result = Rhea.run("cmd.exe", "/c", "echo", "one word")
        assertIs<ProcessResult.Completed>(result)
        assertTrue(result.output.contains("one word"))
    }

    @Test
    fun `the separate capture attributes the streams`() {
        // cmd's echo goes to stdout; a failing command's complaint
        // rides stderr
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo answered & dir does-not-exist-at-all.txt",
                options = RunOptions(capture = CaptureMode.SEPARATE),
            )
        assertIs<ProcessResult.Completed>(result)
        assertTrue(result.stdout!!.contains("answered"))
        assertTrue(result.stderr!!.isNotEmpty())
    }

    @Test
    fun `a missing command is the boundary's failure`() {
        assertFailsWith<ProcessException> {
            Rhea.run("C:/no/such/command/anywhere.exe")
        }
    }

    @Test
    fun `the machine name answers in one line`() {
        val result = Rhea.run("hostname")
        assertIs<ProcessResult.Completed>(result)
        // the trailing newline's face: the trim's the answer's
        // friend
        assertEquals(
            1,
            result.output
                .trimEnd()
                .lines()
                .size,
        )
        assertTrue(result.output.isNotBlank())
    }
}
