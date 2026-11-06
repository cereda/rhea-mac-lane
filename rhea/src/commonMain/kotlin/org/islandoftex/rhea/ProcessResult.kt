// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The run's verdict: the capture survives every outcome.
 *
 * Any exit code is an *answer* — a non-zero exit is the tool's own
 * vocabulary, not the boundary's failure. The boundary's failures
 * (a missing command, a refused spawn) throw
 * [ProcessException] instead — the session's broken pipe carries
 * the capture so far on the exception itself.
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
     * pipe erased the attribution) and under [CaptureMode.NONE]
     * (no pipes exist — the capture stays empty).
     * @property stderr the attributed stderr — the same mode's
     * terms.
     */
    public class Completed(
        public val exitCode: Int,
        override val output: String,
        public val stdout: String? = null,
        public val stderr: String? = null,
    ) : ProcessResult()

    /**
     * The boundary destroyed the process: the caller's
     * [RunOptions.timeout] expired, or the session's own destroyer
     * ([Session.destroy]) pulled the plug — the reason's own
     * vocabulary ([DestroyReason.TIMEOUT], [DestroyReason.DESTROYED])
     * speaks which. The partial capture survives — a destroyed
     * tool's output is often exactly the story.
     *
     * @property reason why the boundary pulled the plug.
     */
    public class Destroyed(
        override val output: String,
        public val reason: DestroyReason,
    ) : ProcessResult()
}
