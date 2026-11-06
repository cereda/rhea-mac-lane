// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The common faces: the caller's own failures, proven once for
 * every target (the check rides the facade's common code — the
 * JVM's smoke suite spoke it alone until now).
 */
class RheaCommonFacesTest {
    @Test
    fun `a blank executable is the caller's failure`() {
        assertFailsWith<ProcessException> { Rhea.run("  ") }
    }

    @Test
    fun `a blank session's executable is the caller's failure`() {
        assertFailsWith<ProcessException> { Rhea.start("  ") }
    }
}
