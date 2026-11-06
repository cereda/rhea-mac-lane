// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.islandoftex.rhea

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.realpath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// realpath's buffer: PATH_MAX's generous face (4096 answers both
// Linux's and macOS's limits)
private const val PATH_BUFFER = 4096

/**
 * The path's canon: `realpath`'s answer — the child's `getcwd`
 * speaks physical paths, and the macOS `/tmp` → `/private/tmp`
 * symlink is the witness the raw string comparison missed.
 */
private fun realPath(path: String): String =
    memScoped {
        val buffer = allocArray<ByteVar>(PATH_BUFFER)
        realpath(path, buffer)?.toKString() ?: path
    }

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
        println("PROBE pwd: verdict ${result::class.simpleName} output=${result.output.trimEnd()}")
        assertIs<ProcessResult.Completed>(result)
        // the physical canon: the child's getcwd resolves symlinks
        // (the macOS /tmp -> /private/tmp's face)
        assertEquals(realPath("/tmp"), result.output.trimEnd())
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
                args = listOf("-c", "echo answered; sleep 0.3; echo complained >&2"),
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
        println("PROBE timeout: verdict ${result::class.simpleName}")
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
}
