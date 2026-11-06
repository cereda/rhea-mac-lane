// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
    fun `the fast child outruns the watchdog and speaks Completed`() {
        val result =
            Rhea.run(
                "/bin/echo",
                "quick",
                options = RunOptions(timeout = 500.milliseconds),
            )
        // the probe's reap must not steal the verdict: the child
        // that finished inside the timeout speaks Completed, with
        // its exit code intact
        assertIs<ProcessResult.Completed>(result)
        assertEquals("quick", result.output)
    }

    @Test
    fun `the closed stdin is an immediate end of stream`() {
        // the cat's face: the immediate EOF, the silent answer —
        // the POSIX's null device wired into the child's stdin
        val result =
            Rhea.run(
                "/bin/cat",
                options = RunOptions(stdin = Stdin.Closed),
            )
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
    }

    @Test
    fun `a refused directory's story rides the capture`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo never"),
                options = RunOptions(directory = "/no/such/directory/anywhere"),
            )
        // the chdir's story: the child spoke the refusal to the
        // captured pipe before exiting — the explanation arrives
        // in the result, not the parent's raw stderr
        assertIs<ProcessResult.Completed>(result)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("refused"))
    }

    @Test
    fun `a chatty child does not deadlock the drains`() {
        // the pipe buffers are ~64KiB: a child writing past them
        // blocks unless the drains run concurrently — the detached
        // pthreads answer the question
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "yes rhea | head -c 200000 > /dev/stdout; echo done >&2"),
            )
        assertIs<ProcessResult.Completed>(result)
        assertTrue(result.output.contains("done"))
    }

    @Test
    fun `the console face tells the raw child's story and captures nothing`() {
        val result =
            Rhea.run(
                "/bin/sh",
                args = listOf("-c", "echo visible >&1; echo raw >&2"),
                options = RunOptions(capture = CaptureMode.NONE),
            )
        // no pipes exist: the child's streams are the parent's own
        // fds (the console sees the story live), the capture side
        // answers its empty faces
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
        assertEquals(null, result.stdout)
        assertEquals(null, result.stderr)
    }

    @Test
    fun `the console face accepts a pumped stdin beside the raw streams`() {
        // the wiring's hard face: the stdin rides its own pipe while
        // the streams ride the parent's handles — the mixed face the
        // interactive tools live in
        val result =
            Rhea.run(
                "/bin/cat",
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
}

/**
 * The byte sink's faces: the raw chunks arriving below the line
 * level, the attribution the pipe speaks.
 */
class RheaPosixByteSinkTest {
    @Test
    fun `the byte sink hears the newline-less prompt while the child lives`() {
        // the byte face's law: the prompt rides no newline, so the
        // line split never speaks it while the child runs; the raw
        // chunks answer the moment the printf's write lands
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
            "/bin/echo",
            options = RunOptions(byteSink = { _, stream -> attributed.add(stream) }),
        )
        assertEquals(setOf(Stream.OUT), attributed)
    }
}
