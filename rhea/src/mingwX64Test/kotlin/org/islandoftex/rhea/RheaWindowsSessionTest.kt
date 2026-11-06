// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

package org.islandoftex.rhea

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.windows.CloseHandle
import platform.windows.CreateJobObjectW
import platform.windows.DWORD
import platform.windows.DWORDVar
import platform.windows.FALSE
import platform.windows.GetCurrentProcess
import platform.windows.GetLastError
import platform.windows.GetProcessHandleCount
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.JOBOBJECT_BASIC_LIMIT_INFORMATION
import platform.windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
import platform.windows.QueryInformationJobObject
import platform.windows.SetInformationJobObject
import platform.windows.TRUE
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The session's faces on the Windows tier: the findstr
 * conversation (the write/EOF/arrival face), the destroyer's
 * vocabulary, the timeout's ladder.
 */
class RheaWindowsSessionTest {
    @Test
    fun `the session converses with findstr`() {
        // the fixture's law: findstr (System32's own stdin reader,
        // exit 0 on match); the session's writes feed its pipe, the
        // spoken end lets it finish, the drain hears the match.
        // The pattern rides as its own plain word — the embedded
        // quotes' face (the cmd /c's own quote-strip mire) proved
        // the poison pill the hard way (the run #27's exit 1)
        val session = Rhea.start("cmd.exe", "/c", "findstr", "fed")
        session.write("fed by the session\r\n")
        session.endOfInput()
        val result = session.waitFor()
        println("the findstr conversation's verdict: $result")
        assertIs<ProcessResult.Completed>(result)
        assertEquals(0, result.exitCode, "the verdict: $result")
        assertTrue(result.output.trimEnd().contains("fed"), "the arrival broke: ${result.output}")
    }

    @Test
    fun `the session's destroy speaks DESTROYED`() {
        // the ping's face: the sleeper whose budget survives the
        // conversation; the destroyer's own watchdog (ms=0) rules
        val session = Rhea.start("cmd.exe", "/c", "ping -n 60 127.0.0.1")
        session.destroy()
        val result = session.waitFor()
        println("the destroy's verdict: $result")
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.DESTROYED, result.reason)
    }

    @Test
    fun `the session rides the timeout's ladder`() {
        // the cold spawn eats seconds (the fixture's patience): the
        // budget stays generous, the ladder still rules first
        val session = Rhea.start("cmd.exe", "/c", "ping -n 60 127.0.0.1", options = RunOptions(timeout = 5.seconds))
        val result = session.waitFor()
        assertIs<ProcessResult.Destroyed>(result)
        assertEquals(DestroyReason.TIMEOUT, result.reason)
    }

    @Test
    fun `the session's pid is the child's own`() {
        val session = Rhea.start("cmd.exe", "/c", "ping -n 5 127.0.0.1")
        assertTrue(session.pid > 0)
        session.destroy()
        assertIs<ProcessResult.Destroyed>(session.waitFor())
    }

    @Test
    fun `the unfenced child runs the daemon's door`() {
        // the fence's flag at false: no job rides the spawn (the
        // orphan protection's absence is the documented face) — the
        // destroyer's ladder still speaks on demand
        val session =
            Rhea.start(
                "cmd.exe",
                "/c",
                "ping -n 5 127.0.0.1",
                options = RunOptions(killOnParentDeath = false),
            )
        assertTrue(session.pid > 0)
        session.destroy()
        assertIs<ProcessResult.Destroyed>(session.waitFor())
    }

    @Test
    fun `the environment's override replaces the inherited face`() {
        // the dedup's own proof: NUMBER_OF_PROCESSORS rides every
        // Windows environment — the override must REPLACE it (the
        // CRT's undefined pick if both ride the block). The set's
        // own listing = the child's environment's truth: 42 means
        // the block's face (the dedup, the sort) landed. The
        // %NUMBER_OF_PROCESSORS% expansion is NOT this test's
        // business: the kernel re-sets that variable per process
        // (the processor-group's own face), answering the real
        // count no matter what the block said — the Windows' own
        // vocabulary, never rhea's
        val listed =
            Rhea.run(
                "cmd.exe",
                "/c",
                "set",
                "NUMBER_OF_PROCESSORS",
                options = RunOptions(environment = mapOf("NUMBER_OF_PROCESSORS" to "42")),
            )
        assertIs<ProcessResult.Completed>(listed)
        assertTrue(
            listed.output.contains("NUMBER_OF_PROCESSORS=42"),
            "the block's face broke: ${listed.output}",
        )
    }

    @Test
    fun `the quote's escape rides the wordy argv`() {
        // the quote()'s own face: a word with literal quotes
        // arrives as ONE argv word (the Microsoft rules' escape:
        // \" — the old face split the word at every quote); the
        // cmd's echo prints the rest of its line verbatim, so the
        // escape's own face is the proof
        val result =
            Rhea.run(
                "cmd.exe",
                "/c",
                "echo",
                "say \"hello\" loud",
            )
        assertIs<ProcessResult.Completed>(result)
        assertTrue(result.output.contains("\\\"hello\\\""), "the escape's face broke: ${result.output}")
    }

    @Test
    fun `the job's machinery speaks its numbers`() {
        // the fence's own face, isolated: the zero-first
        // discriminator (the Set's return lies — the lanes' 24/87 —
        // so the read-back is the only truth): zeroed flags, the
        // Set, the Query's read-back (0 = the write landed), then
        // the real face's set and the read-back again (8192 = the
        // kill-on-close's own guarantee)
        memScoped {
            val job = CreateJobObjectW(null, null)
            assertTrue(job != null && job != INVALID_HANDLE_VALUE, "the job's handle: $job")
            val limits = alloc<JOBOBJECT_BASIC_LIMIT_INFORMATION>()
            val size = sizeOf<JOBOBJECT_BASIC_LIMIT_INFORMATION>().convert<DWORD>()
            limits.LimitFlags = 0u.convert<DWORD>()
            SetInformationJobObject(job, 3u.convert<DWORD>(), limits.ptr, size)
            QueryInformationJobObject(job, 3u.convert<DWORD>(), limits.ptr, size, null)
            val afterZero = limits.LimitFlags
            limits.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.convert<DWORD>()
            SetInformationJobObject(job, 3u.convert<DWORD>(), limits.ptr, size)
            QueryInformationJobObject(job, 3u.convert<DWORD>(), limits.ptr, size, null)
            val afterSet = limits.LimitFlags
            CloseHandle(job)
            assertTrue(
                afterZero == 0u.convert<DWORD>() && afterSet == JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.convert<DWORD>(),
                "zero: flags=$afterZero | set: flags=$afterSet (the expected: 0 then 8192)",
            )
        }
    }

    @Test
    fun `the handles' ledger speaks the leaks`() {
        // the createpipe's 6's own question: the process's handle
        // count before and after a full run — the leak's own
        // arithmetic rides the failure's message home
        memScoped {
            val before = alloc<DWORDVar>()
            GetProcessHandleCount(GetCurrentProcess(), before.ptr)
            Rhea.run("cmd.exe", "/c", "echo the ledger's own run")
            val after = alloc<DWORDVar>()
            GetProcessHandleCount(GetCurrentProcess(), after.ptr)
            assertTrue(
                after.value - before.value < 5u,
                "the handles' face: before=${before.value} after=${after.value}",
            )
        }
    }

    @Test
    fun `the second hand's verdict rides the gate`() {
        // the gate's own proof: two hands ask the verdict at
        // once — the first builds (the drains, the wait, the
        // handles' close), the second's patience answers the
        // SAME verdict (the old shape re-closed the handles)
        val session = Rhea.start("cmd.exe", "/c", "ping -n 2 127.0.0.1")
        val worker = Worker.start()
        val second = worker.execute(TransferMode.SAFE, { session }) { it.waitFor() }
        val first = session.waitFor()
        assertIs<ProcessResult.Completed>(first)
        assertTrue(first === second.result, "the gate's face broke")
        worker.requestTermination()
    }

    @Test
    fun `the write after the spoken end answers the contract`() {
        // the contract's own face: the write after endOfInput is
        // the IllegalStateException — the child may live; the
        // caller's own hand spoke the end
        val session = Rhea.start("cmd.exe", "/c", "findstr", "fed")
        session.endOfInput()
        assertFailsWith<IllegalStateException> { session.write("late\n") }
        session.waitFor()
    }
}
