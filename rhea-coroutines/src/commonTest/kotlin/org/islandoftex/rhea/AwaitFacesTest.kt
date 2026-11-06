// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The await pair's common faces: the caller's own failures,
 * proven once for every target (the checks ride the facade's
 * common code).
 */
class AwaitFacesTest {
    @Test
    fun `a blank await's executable is the caller's failure`() {
        runBlocking {
            assertFailsWith<ProcessException> { Rhea.await("  ") }
        }
    }

    @Test
    fun `a blank session's executable is the caller's failure`() {
        runBlocking {
            assertFailsWith<ProcessException> { Rhea.start("  ") }
        }
    }
}
