// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The break face's pace: the child (sleep 0.3) long gone, the pipe's dead. */
private const val PIPE_BREAK_PACE_MS = 1000L

/**
 * The session's faces on the JVM: the conversation in order, the
 * destroyer's vocabulary, the timeout's ladder, the stdin's own
 * law (always a pipe).
 */
class RheaSessionSmokeTest {
    @Test
    fun `the session converses in order`() {
        // the conversational probe: two turns, the child's answers
        // arriving in the writes' own order (the sh builtin's read
        // speaks byte-by-byte — no buffered middleman)
        val session =
            Rhea.start("/bin/sh", "-c", "read one; echo saw-\$one; read two; echo saw-\$two")
        session.write("first\n")
        session.write("second\n")
        session.endOfInput()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        val sawFirst = result.output.indexOf("saw-first")
        val sawSecond = result.output.indexOf("saw-second")
        assertTrue(sawFirst >= 0 && sawSecond > sawFirst, "the turns' order broke: ${result.output}")
    }

    @Test
    fun `the session's destroy speaks DESTROYED`() {
        val session = Rhea.start("/bin/sleep", "60")
        session.destroy()
        val result = session.waitFor()
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.DESTROYED, result.reason)
    }

    @Test
    fun `the session rides the timeout's ladder`() {
        val session = Rhea.start("/bin/sleep", "60", options = RunOptions(timeout = 300.milliseconds))
        val result = session.waitFor()
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.TIMEOUT, result.reason)
    }

    @Test
    fun `the session's stdin is always a pipe`() {
        // the loud contract: the RunOptions' stdin policy is not
        // honored — even Stdin.Closed cannot take the pipe away;
        // the session's writes flow regardless
        val session = Rhea.start("/bin/cat", options = RunOptions(stdin = Stdin.Closed))
        session.write("wired anyway\n")
        session.endOfInput()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertTrue(result.output.contains("wired anyway"))
    }

    @Test
    fun `the session's merged capture converses with a sink`() {
        // the merged conversation: one drain, the UNKNOWN
        // attribution, the sink hearing every line live; the
        // attributed fields answer null (the pipe erased them)
        val heard = mutableListOf<String>()
        val session =
            Rhea.start(
                "/bin/sh",
                "-c",
                "echo one; echo complained >&2",
                options =
                    RunOptions(
                        capture = CaptureMode.MERGED,
                        sink = { line, _ -> heard.add(line) },
                    ),
            )
        session.endOfInput()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("one") && result.output.contains("complained"))
        assertEquals(2, heard.size)
        assertEquals(null, result.stdout)
        assertEquals(null, result.stderr)
    }

    @Test
    fun `the session's destroy escalates past the ignored TERM`() {
        // the ladder's full face: the child traps TERM (the
        // graceful kill answers nothing), the grace expires, the
        // forceful escalation lands
        val session = Rhea.start("/bin/sh", "-c", "trap \"\" TERM; while :; do sleep 1; done")
        session.destroy()
        val result = session.waitFor()
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.DESTROYED, result.reason)
    }

    @Test
    fun `the session's pid is the child's own`() {
        val session = Rhea.start("/bin/sleep", "60")
        assertTrue(session.pid > 0)
        session.destroy()
        assertIs<ProcessResult.Destroyed>(session.waitFor())
    }

    @Test
    fun `the daemon's door opens on demand`() {
        // the fence's flag at false: no shutdown hook rides the
        // JVM's exit — the child's life is its own (the run's
        // verdict still speaks; sleep 0 answers zero)
        val session =
            Rhea.start("/bin/sleep", "0", options = RunOptions(killOnParentDeath = false))
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `the run's big stdin breaks through the verdict`() {
        // the pump's swallow's proof: the child never reads (the
        // sleep's face), the stdin's 200K outgrow the pipe's
        // buffer, the write blocks — the timeout's ladder kills
        // the child, the broken pipe answers the pump, and the
        // verdict speaks Destroyed (the exception never swallowed
        // the run's own answer)
        val result =
            Rhea.run(
                "/bin/sleep",
                "5",
                options =
                    RunOptions(
                        stdin = Stdin.From(ByteArray(200_000)),
                        timeout = 300.milliseconds,
                    ),
            )
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.TIMEOUT, result.reason)
    }

    @Test
    fun `the session's waitFor answers twice alike`() {
        // the cache's face: the re-ask answers the SAME verdict —
        // the POSIX tier's own guard against the freed-memory
        // re-entry, this one its consistency
        val session = Rhea.start("/bin/true", "0")
        val first = session.waitFor()
        val second = session.waitFor()
        assertTrue(first === second, "the cache's face broke")
    }
}

/**
 * The session's edge faces: the blank executable, the defaults,
 * the spoken end's once-ness, the destroyer's patience on an
 * exited child, the console face's conversation, the ruling's
 * vocabulary.
 */
class RheaSessionEdgesTest {
    @Test
    fun `a blank executable is the caller's failure`() {
        assertFailsWith<ProcessException> { Rhea.start("  ") }
    }

    @Test
    fun `the defaults' face starts a bare conversation`() {
        // no args, no options: the defaults' synthetic faces — the
        // child finishes first, the verdict is its own
        val session = Rhea.start("/bin/sleep", "0")
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `the spoken end answers once`() {
        val session = Rhea.start("/bin/cat")
        session.endOfInput()
        assertFailsWith<IllegalStateException> { session.endOfInput() }
        session.waitFor()
    }

    @Test
    fun `destroying an exited child answers its own exit`() {
        // the child's own exit speaks, not the boundary's hand:
        // the destroyer's patience never rules a gone child
        val session = Rhea.start("/bin/sleep", "0")
        session.waitFor()
        session.destroy()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `the session's console face converses raw`() {
        // the raw capture: no pipes exist, the child's streams ride
        // the parent's handles — but the session's stdin stays its
        // own pipe (the byte face's write included)
        val session =
            Rhea.start(
                "/bin/cat",
                options =
                    RunOptions(
                        capture = CaptureMode.NONE,
                        stdin = Stdin.Closed,
                    ),
            )
        session.write("console face\n".encodeToByteArray())
        session.endOfInput()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
        assertEquals(null, result.stdout)
        assertEquals(null, result.stderr)
    }

    @Test
    fun `the ruling codes speak their reasons`() {
        assertEquals(null, reasonOf(RULED_NONE))
        assertEquals(DestroyReason.TIMEOUT, reasonOf(RULED_TIMEOUT))
        assertEquals(DestroyReason.DESTROYED, reasonOf(RULED_DESTROYED))
    }

    @Test
    fun `the timeout's watchdog wakes to an exited child`() {
        // the child finishes first: the watchdog's wake rules
        // nothing (the isAlive probe's false face)
        val session = Rhea.start("/bin/sleep", "0", options = RunOptions(timeout = 500.milliseconds))
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `the write after the spoken end answers the contract`() {
        // the contract's own face: the write after endOfInput is
        // the IllegalStateException — the child may live; the
        // caller's own hand spoke the end (the old face answered
        // the child's-gone ProcessException on a closed stream)
        val session = Rhea.start("/bin/cat")
        session.endOfInput()
        assertFailsWith<IllegalStateException> { session.write("late\n") }
        session.waitFor()
    }

    @Test
    fun `the session's write answers the broken pipe`() {
        // the break face: the child exits (its death IS the read
        // end's death), the parent's write rides the broken pipe —
        // the ProcessException is the uniform answer (the POSIX's
        // own test speaks the same face)
        val session = Rhea.start("/bin/sleep", "0.3")
        Thread.sleep(PIPE_BREAK_PACE_MS)
        assertFailsWith<ProcessException> { session.write("into the void\n") }
        session.destroy()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
    }
}
