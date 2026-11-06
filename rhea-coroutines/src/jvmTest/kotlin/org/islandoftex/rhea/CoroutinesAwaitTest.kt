// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The await's park: the hook's armed, the plug's pull is next. */
private const val AWAIT_PACE_MS = 100L

/**
 * The await pair's faces on the JVM: the run's verdict, the
 * session's conversation, the cancellation's own plug-pull.
 */
class CoroutinesAwaitTest {
    @Test
    fun `the run's await answers the verdict`() =
        runBlocking {
            val result = Rhea.await("/bin/sh", args = listOf("-c", "exit 7"))
            assertIs<ProcessResult.Completed>(result)
            assertEquals(7, result.exitCode)
        }

    @Test
    fun `the session's await answers the conversation`() =
        runBlocking {
            val session = Rhea.start("/bin/sh", "-c", "echo done")
            val result = session.await()
            assertIs<ProcessResult.Completed>(result)
            assertTrue(result.output.contains("done"), "the capture broke: ${result.output}")
        }

    @Test
    fun `the await answers twice alike`() =
        runBlocking {
            val session = Rhea.start("/bin/sh", "-c", "exit 0")
            val first = session.await()
            val second = session.await()
            assertTrue(first === second, "the cache's face broke")
        }

    @Test
    fun `the session's await rides the timeout's ladder`() =
        runBlocking {
            // the ladder's vocabulary through the wrapper's face:
            // the timeout's own destroyer rules, the await answers
            // Destroyed(TIMEOUT) — the wrapper never blurs the
            // reasons
            val session = Rhea.start("/bin/sleep", "60", options = RunOptions(timeout = 300.milliseconds))
            val verdict = session.await()
            assertIs<ProcessResult.Destroyed>(verdict)
            assertEquals(DestroyReason.TIMEOUT, verdict.reason)
        }

    @Test
    fun `the cancellation pulls the plug`() =
        runBlocking {
            val session = Rhea.start("/bin/sleep", "60")
            val job = launch { session.await() }
            delay(AWAIT_PACE_MS) // the await's park: the hook's armed
            job.cancel()
            // the cancellation's own doing: the child's dead, the
            // verdict speaks DESTROYED
            val result = session.waitFor()
            assertIs<ProcessResult.Destroyed>(result)
            assertEquals(DestroyReason.DESTROYED, result.reason)
        }
}
