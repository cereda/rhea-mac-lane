// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        // the equality's face: the contains-check was vacuous on
        // any C:-drive checkout (the GH lane's D: spoke the truth)
        assertEquals("C:\\", result.output.trimEnd())
    }

    @Test
    fun `the environment overrides ride the inherited one`() {
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo %RHEA_PROBE%",
                options = RunOptions(environment = mapOf("RHEA_PROBE" to "injected")),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals("injected", result.output.trimEnd())
    }

    @Test
    fun `the fast child outruns the watchdog and speaks Completed`() {
        // the timeout's patience: the GH runner's cold cmd.exe
        // spawn eats seconds (the Defender's scan), so the
        // generous ceiling speaks the face honestly
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo quick",
                options = RunOptions(timeout = 5.seconds),
            )
        assertIs<ProcessResult.Completed>(result)
        // the \r's face: the Windows lines end \r\n, the capture
        // trims only the \n — the trim's the assertion's friend
        assertEquals("quick", result.output.trimEnd())
    }

    @Test
    fun `the closed stdin is an immediate end of stream`() {
        // the more's face: the child reads its (unwired) stdin,
        // meets the end of stream, and the run returns — the
        // no-hang IS the assertion
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "more",
                options = RunOptions(stdin = Stdin.Closed),
            )
        assertIs<ProcessResult.Completed>(result)
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

    @Test
    fun `the console face tells the raw child's story and captures nothing`() {
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo visible",
                options = RunOptions(capture = CaptureMode.NONE),
            )
        // no pipes exist: the child's streams are the parent's own
        // handles (the console sees the story live), the capture
        // side answers its empty faces
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
        assertNull(result.stdout)
        assertNull(result.stderr)
    }

    @Test
    fun `the console face accepts a pumped stdin beside the raw streams`() {
        // the wiring's hard face: the stdin rides its own pipe
        // while the streams ride the parent's handles — the
        // findstr's verdict speaks the pipe's read (exit 0: 'fed'
        // was in the pumped bytes) and the console holds the
        // output. (findstr, not find: the runner's PATH rides GNU
        // find today, and the GNU find reads 'fed' as a directory
        // to search — the child's console face told the story.)
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "findstr",
                "fed",
                options =
                    RunOptions(
                        capture = CaptureMode.NONE,
                        stdin = Stdin.From("fed by rhea\n".encodeToByteArray()),
                    ),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
    }

    @Test
    fun `the inherited stdin is the parent's real handle`() {
        // the fix's face: GetStdHandle speaks the parent's stdin —
        // the INVALID_HANDLE_VALUE fiction is gone. The runners'
        // stdin varies (a console on the user's box, the CI's
        // closed face): what the test insists on is the return
        // itself — a spawn refusal speaks ProcessException, and
        // whatever the child reads, the ceiling stands patient
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "more",
                options = RunOptions(stdin = Stdin.Inherit, timeout = 20.seconds),
            )
        when (result) {
            is ProcessResult.Completed -> {}

            is ProcessResult.Destroyed -> {
                assertEquals(DestroyReason.TIMEOUT, result.reason)
            }
        }
    }
}

/**
 * The byte sink's face: the raw chunks exactly as ReadFile handed
 * them — no line split waits for the newline.
 */
class RheaWindowsByteSinkTest {
    @Test
    fun `the byte sink hears the raw chunks`() {
        val heardRaw = StringBuilder()
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo hello",
                options = RunOptions(byteSink = { chunk, _ -> heardRaw.append(chunk.decodeToString()) }),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertTrue(heardRaw.toString().contains("hello"))
    }
}
