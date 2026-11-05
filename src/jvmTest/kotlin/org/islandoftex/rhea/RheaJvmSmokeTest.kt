// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The JVM runner's smoke: real children, the typed verdicts —
 * the attribution, the merge, the stdin's policies, the boundary's
 * failure.
 */
class RheaJvmSmokeTest {
    @Test
    fun `a child runs and its verdict carries the exit code`() {
        val result = Rhea.run("echo", "hello", "rhea")
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("hello rhea", result.output)
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
        // the attribution's price: the fields answer null
        assertEquals(null, result.stdout)
        assertEquals(null, result.stderr)
    }

    @Test
    fun `a non-zero exit is an answer, not a failure`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo the story; exit 3"),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(3, result.exitCode)
        assertTrue(result.output.contains("the story"))
    }

    @Test
    fun `a missing command is the boundary's failure`() {
        val exception =
            assertFailsWith<ProcessException> {
                Rhea.run("/no/such/command/anywhere")
            }
        assertTrue(exception.message!!.contains("could not"))
    }

    @Test
    fun `the closed stdin isolates the child`() {
        // a tool that reads its stdin sees the immediate end of
        // stream: cat answers nothing and exits
        val result =
            Rhea.run(
                "cat",
                options = RunOptions(stdin = Stdin.Closed),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
    }

    @Test
    fun `a blank executable is the caller's failure`() {
        assertFailsWith<ProcessException> {
            Rhea.run("  ")
        }
    }
}
