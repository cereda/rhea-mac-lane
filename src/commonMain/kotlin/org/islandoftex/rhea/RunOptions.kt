// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The run's modifiers. The unset ones stay out of the machinery.
 *
 * @property capture how the child's streams reach the result —
 *   [CaptureMode.SEPARATE] (the default) keeps the attribution,
 *   [CaptureMode.MERGED] preserves the kernel's interleaving.
 * @property stdin the child's stdin policy — [Stdin.Inherit] (the
 *   default), [Stdin.Closed], or [Stdin.From] (the pumped bytes).
 * @property directory the working directory the child runs in
 *   (the platform's own path spelling); null: the parent's.
 * @property environment the environment variables the child's
 *   inherited environment gains or overrides.
 * @property sink the live consumer of the child's lines (a
 *   logger, the console, a file) alongside the capture; null:
 *   the silent capture.
 * @property timeout how long the child may run before the
 *   boundary destroys it (the graceful kill first, the forceful
 *   escalation after); null: the child runs as long as it wants.
 */
public data class RunOptions(
    public val capture: CaptureMode = CaptureMode.SEPARATE,
    public val stdin: Stdin = Stdin.Inherit,
    public val directory: String? = null,
    public val environment: Map<String, String> = emptyMap(),
    public val sink: Sink? = null,
    public val timeout: kotlin.time.Duration? = null,
)
