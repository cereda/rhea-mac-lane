// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Windows tier's smoke: the timeout's destroy escalation
 * against a real child (`ping`'s -n count = the sleep's Windows
 * face), the chatty child's drain's caution, and the cwd's face.
 */
class RheaWindowsTiersSmokeTest {
    // the sleeper's face: the ping's own (the 127.0.0.1's loopback,
    // the -n's count = the ~1s per)
    private val loopback = "127.0.0.1"
    private val pingCount = "-n"
    private val destroyAt = 500.milliseconds

    // the sleeper's count: the ping's ~30s wait's face
    @Suppress("MagicNumber")
    private val sleepCount = 30

    private fun sleeper(count: Int) = listOf("ping", loopback, pingCount, "$count")

    @Test
    fun `the timeout destroys a sleeping child and speaks Destroyed`() {
        val result =
            Rhea.run(
                executable = sleeper(sleepCount).first(),
                args = sleeper(sleepCount).drop(1),
                options = RunOptions(timeout = destroyAt),
            )
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.TIMEOUT, result.reason)
    }

    @Test
    fun `the destroyed child's partial capture survives`() {
        // the echo's first, the sleep's second: the story's printed,
        // then the ping's the wait — the destroy's at the 500ms's
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo the story & ping 127.0.0.1 -n 30 > nul",
                options = RunOptions(timeout = destroyAt),
            )
        assertIs<ProcessResult.Destroyed>(result)
        assertTrue(result.output.contains("the story"))
    }

    @Test
    fun `the working directory is the child's cwd`() {
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "cd",
                options = RunOptions(directory = "C:\\"),
            )
        assertIs<ProcessResult.Completed>(result)
        assertTrue(result.output.contains("C:\\"), "the cwd's answer: ${result.output}")
    }

    @Test
    fun `a chatty child's small burst does not deadlock the drains`() {
        // the pipe buffers' face: the burst's small face (the full
        // chatty's = the next tier's)
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo one & echo two & echo three",
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("three"))
    }
}
