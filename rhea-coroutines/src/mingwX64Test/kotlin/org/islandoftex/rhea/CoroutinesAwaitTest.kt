// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The await pair's faces on the Windows tier: the Win32
 * machinery's own verdicts through the suspending surface.
 */
class CoroutinesAwaitTest {
    @Test
    fun `the run's await answers the verdict`() =
        runBlocking {
            // the verdict's face: the child's own answers ride home.
            // The child is the system32's own `where`: the found's
            // face (the exit 0 + the path in the capture) and the
            // missing's face (the not-found INFO in the capture).
            // The where's own exit code for the not-found answers 0
            // on some loaded hosts (documented 1 elsewhere) — the
            // tool's own inconsistency under load, never rhea's:
            // the capture is the claim, the exit code's the found's
            // own
            val found = Rhea.await("where", args = listOf("cmd.exe"))
            assertIs<ProcessResult.Completed>(found)
            assertEquals(0, found.exitCode, "the found's exit: ${found.output}")
            assertTrue(found.output.contains("cmd.exe"), "the found's capture: ${found.output}")

            val missing = Rhea.await("where", args = listOf("no-such-tool-anywhere"))
            assertIs<ProcessResult.Completed>(missing)
            assertTrue(
                missing.output.contains("Could not find"),
                "the missing's capture: ${missing.output}",
            )
        }

    @Test
    fun `the session's await answers the conversation`() =
        runBlocking {
            val session = Rhea.start("cmd.exe", "/c", "echo done")
            val result = session.await()
            assertIs<ProcessResult.Completed>(result)
            assertEquals(0, result.exitCode)
        }
}
