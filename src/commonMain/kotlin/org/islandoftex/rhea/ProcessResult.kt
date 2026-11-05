// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The run's verdict: the capture survives every outcome.
 *
 * Any exit code is an *answer* — a non-zero exit is the tool's own
 * vocabulary, not the boundary's failure. The boundary's failures
 * (a missing command, a refused spawn) throw
 * [ProcessException] instead, and they carry the partial capture:
 * the output printed before the failure is exactly what explains
 * it.
 */
public sealed class ProcessResult {
    /** The capture — the fact that explains the verdict. */
    public abstract val output: String

    /**
     * The process ran to completion.
     *
     * @property exitCode the tool's own answer (zero, failure
     * codes, the shell's 127 conventions — all vocabulary).
     * @property stdout the attributed stdout — populated under
     * [CaptureMode.SEPARATE], null under [CaptureMode.MERGED] (the
     * pipe erased the attribution).
     * @property stderr the attributed stderr — the same mode's
     * terms.
     */
    public class Completed(
        public val exitCode: Int,
        override val output: String,
        public val stdout: String? = null,
        public val stderr: String? = null,
    ) : ProcessResult()
}
