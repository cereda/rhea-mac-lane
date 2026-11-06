// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The tiers' smoke: the working directory, the environment, the
 * sink's attributions, the pumped stdin, and the timeout's
 * destroy escalation — the faces the consumers asked for.
 */
class RheaTiersSmokeTest {
    @Test
    fun `the working directory is the child's pwd`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "pwd"),
                options = RunOptions(directory = "/tmp"),
            )
        assertIs<ProcessResult.Completed>(result)
        // the physical canon: the child's getcwd resolves symlinks
        // (the macOS /tmp -> /private/tmp's face)
        assertEquals(File("/tmp").canonicalPath, result.output.trimEnd())
    }

    @Test
    fun `the environment overrides ride the inherited one`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo \$RHEA_PROBE"),
                options = RunOptions(environment = mapOf("RHEA_PROBE" to "injected")),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals("injected", result.output.trimEnd())
    }

    @Test
    fun `the sink hears the lines with their attributions`() {
        val heard = mutableListOf<Pair<String, Stream>>()
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo answered; echo complained >&2"),
                options = RunOptions(sink = { line, stream -> heard.add(line to stream) }),
            )
        assertIs<ProcessResult.Completed>(result)
        // the attribution's the contract: the cross-stream arrival
        // order is the drains' whim (the MERGED capture's job to
        // settle), so the expectation speaks the set
        assertEquals(
            setOf("answered" to Stream.OUT, "complained" to Stream.ERR),
            heard.toSet(),
        )
    }

    @Test
    fun `the merged capture's sink speaks unknown`() {
        val heard = mutableListOf<Stream>()
        Rhea.run(
            "echo",
            "hello",
            options =
                RunOptions(
                    capture = CaptureMode.MERGED,
                    sink = { _, stream -> heard.add(stream) },
                ),
        )
        assertEquals(listOf(Stream.UNKNOWN), heard)
    }

    @Test
    fun `the pumped stdin feeds the child`() {
        val result =
            Rhea.run(
                "cat",
                emptyList(),
                RunOptions(stdin = Stdin.From("fed by rhea\n".encodeToByteArray())),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals("fed by rhea", result.output)
    }

    @Test
    fun `the timeout destroys the child and speaks Destroyed`() {
        val result =
            Rhea.run(
                "/bin/sleep",
                args = listOf("30"),
                options = RunOptions(timeout = 500.milliseconds),
            )
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.TIMEOUT, result.reason)
    }

    @Test
    fun `the destroyed child's partial capture survives`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo the story; sleep 30"),
                options = RunOptions(timeout = 500.milliseconds),
            )
        assertIs<ProcessResult.Destroyed>(result)
        assertTrue(result.output.contains("the story"))
    }

    @Test
    fun `a refused directory is the boundary's failure`() {
        // the JVM's face: the builder refuses the spawn itself —
        // the exception's the answer (the POSIX tier's twin test
        // speaks the story-in-the-capture face instead)
        assertFailsWith<ProcessException> {
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo never"),
                options = RunOptions(directory = "/no/such/directory/anywhere"),
            )
        }
    }

    @Test
    fun `the fast child outruns the watchdog and speaks Completed`() {
        val result =
            Rhea.run(
                "echo",
                "quick",
                options = RunOptions(timeout = 500.milliseconds),
            )
        // the watchdog's patient face: the timeout's a ceiling, not
        // a verdict — the child that finishes inside it is never
        // touched
        assertIs<ProcessResult.Completed>(result)
        assertEquals("quick", result.output)
    }

    @Test
    fun `the grace's expiry speaks the forcible kill`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "trap \"\" TERM; sleep 2; echo late"),
                options = RunOptions(timeout = 300.milliseconds),
            )
        // the escalation ladder's last rung: the child that traps
        // the graceful kill outlives the grace and meets SIGKILL —
        // the "late" echo never happened
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.TIMEOUT, result.reason)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun `the merged capture without a sink answers the combined`() {
        val result =
            Rhea.run(
                "echo",
                "merged",
                options = RunOptions(capture = CaptureMode.MERGED),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals("merged", result.output)
    }

    @Test
    fun `the console face hands the streams to the parent and captures nothing`() {
        // the builder's INHERIT redirects: the streams ride the
        // parent's own handles, the capture side answers nothing
        val result =
            Rhea.run(
                "echo",
                "visible",
                options = RunOptions(capture = CaptureMode.NONE),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
        assertNull(result.stdout)
        assertNull(result.stderr)
    }

    @Test
    fun `the console face accepts a pumped stdin beside the raw streams`() {
        val result =
            Rhea.run(
                "cat",
                options =
                    RunOptions(
                        capture = CaptureMode.NONE,
                        stdin = Stdin.From("mixed\n".encodeToByteArray()),
                    ),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
    }

    @Test
    fun `the byte sink hears the newline-less prompt while the child lives`() {
        // the byte face's law: the prompt rides no newline, so the
        // line split never speaks it while the child runs; the raw
        // chunks answer the moment the printf's write lands. The
        // child outlives the prompt on purpose: the destroyed run's
        // EOF face would deliver the tail too late to mean "live"
        val heardRaw = mutableListOf<String>()
        Rhea.run(
            "/bin/sh",
            args = listOf("-c", "printf 'question> '; sleep 30"),
            options =
                RunOptions(
                    byteSink = { chunk, _ ->
                        heardRaw.add(chunk.decodeToString())
                    },
                    timeout = 500.milliseconds,
                ),
        )
        assertTrue(heardRaw.joinToString("").contains("question> "))
    }

    @Test
    fun `the byte sink's attribution is the pipe's own`() {
        val attributed = mutableSetOf<Stream>()
        Rhea.run(
            "/bin/sh",
            args = listOf("-c", "echo one"),
            options =
                RunOptions(
                    byteSink = { _, stream -> attributed.add(stream) },
                ),
        )
        assertEquals(setOf(Stream.OUT), attributed)
    }
}
