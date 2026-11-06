// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package org.islandoftex.rhea

import platform.posix.kill
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The break face's pace: the child (sleep 0.3) long gone, the pipe's dead. */
private const val PIPE_BREAK_PACE_MS = 1000L

/** The big stdin's face: twice the pipe's own buffer. */
private const val BIG_STDIN_SIZE = 200_000

/** The gate's own face: the child's exit code (`sleep 0.3; exit N`). */
private const val GATE_EXIT_CODE = 7

/**
 * The session's faces on the POSIX tier: the fork's conversation,
 * the destroyer's vocabulary, the timeout's ladder, the stdin's
 * own law (always a pipe).
 */
class RheaPosixSessionTest {
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
    fun `the session's pid is the child's own`() {
        // the fence's keeper parked on the child's lifetime: the
        // pid answers (the kill's zero-signal probe speaks liveness)
        val session = Rhea.start("/bin/sleep", "60")
        assertTrue(session.pid > 0)
        assertEquals(0, kill(session.pid.toInt(), 0))
        session.destroy()
        assertIs<ProcessResult.Destroyed>(session.waitFor())
    }

    @Test
    fun `the session's write answers the broken pipe`() {
        // the break face: the child exits (its death IS the read
        // end's death — no shell's reopen trick in the way), the
        // parent's write rides the broken pipe. The
        // ProcessException is the uniform answer (the JVM's
        // IOException wears the same vocabulary); the survival of
        // this very test also proves the K/N's own SIGPIPE boot
        // face (an unignored SIGPIPE would kill the test binary
        // mid-write, the lane's red the discovery). The child's
        // own exit spoke before the destroy: the verdict answers
        // Completed — the boundary never rules a gone child.
        val session = Rhea.start("/bin/sleep", "0.3")
        sleepMs(PIPE_BREAK_PACE_MS)
        assertFailsWith<ProcessException> { session.write("into the void\n") }
        session.destroy()
        val result = session.waitFor()
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `the run's big stdin breaks through the verdict`() {
        // the pump's swallow's proof: the child never reads, the
        // stdin's 200K outgrow the pipe's buffer, the write blocks
        // — the timeout's ladder kills the child, the broken pipe
        // answers the pump, and the verdict speaks Destroyed (the
        // exception never swallowed the run's own answer)
        val result =
            Rhea.run(
                "/bin/sleep",
                "5",
                options =
                    RunOptions(
                        stdin = Stdin.From(ByteArray(BIG_STDIN_SIZE)),
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
        // re-entry
        val session = Rhea.start("/bin/sh", "-c", "exit 0")
        val first = session.waitFor()
        val second = session.waitFor()
        assertTrue(first === second, "the cache's face broke")
    }

    @Test
    fun `the second hand's verdict rides the gate`() {
        // the gate's own proof: two hands ask the verdict at
        // once — the first builds (the drains, the reap, the
        // mutex's destroy), the second's patience answers the
        // SAME verdict (the old shape rode freed memory twice)
        val session = Rhea.start("/bin/sh", "-c", "sleep 0.3; exit $GATE_EXIT_CODE")
        val worker = Worker.start()
        val second = worker.execute(TransferMode.SAFE, { session }) { it.waitFor() }
        val first = session.waitFor()
        assertIs<ProcessResult.Completed>(first)
        assertEquals(GATE_EXIT_CODE, first.exitCode)
        assertTrue(first === second.result, "the gate's face broke")
        worker.requestTermination()
    }

    @Test
    fun `the write after the spoken end answers the contract`() {
        // the contract's own face: the write after endOfInput is
        // the IllegalStateException — the child may live; the
        // caller's own hand spoke the end (the old face answered
        // the child's-gone ProcessException on a closed fd)
        val session = Rhea.start("/bin/cat")
        session.endOfInput()
        assertFailsWith<IllegalStateException> { session.write("late\n") }
        session.waitFor()
    }
}
