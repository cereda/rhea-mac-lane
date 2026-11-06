// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The await's own face: the child's exit code. */
private const val AWAIT_EXIT_CODE = 7

/**
 * The await pair's faces on the POSIX tier: the fork machinery's
 * own verdicts through the suspending surface.
 */
class CoroutinesAwaitTest {
    @Test
    fun `the run's await answers the verdict`() =
        runBlocking {
            val result = Rhea.await("/bin/sh", args = listOf("-c", "exit $AWAIT_EXIT_CODE"))
            assertIs<ProcessResult.Completed>(result)
            assertEquals(AWAIT_EXIT_CODE, result.exitCode)
        }

    @Test
    fun `the session's await answers the conversation`() =
        runBlocking {
            val session = Rhea.start("/bin/sh", "-c", "echo done")
            val result = session.await()
            assertIs<ProcessResult.Completed>(result)
            assertEquals(0, result.exitCode)
        }
}
