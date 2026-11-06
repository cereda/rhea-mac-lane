// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlin.time.Duration

/**
 * The run's modifiers. The unset ones stay out of the machinery.
 *
 * @property capture how the child's streams reach the result —
 *   [CaptureMode.SEPARATE] (the default) keeps the attribution,
 *   [CaptureMode.MERGED] preserves the kernel's interleaving,
 *   [CaptureMode.NONE] hands the streams to the parent's own
 *   handles and captures nothing.
 * @property stdin the child's stdin policy — [Stdin.Inherit] (the
 *   default), [Stdin.Closed], or [Stdin.From] (the pumped bytes).
 * @property directory the working directory the child runs in
 *   (the platform's own path spelling); null: the parent's.
 * @property environment the environment variables the child's
 *   inherited environment gains or overrides.
 * @property sink the live consumer of the child's lines (a
 *   logger, the console, a file) alongside the capture; null:
 *   the silent capture. No capture means no lines: the sink is
 *   mute under [CaptureMode.NONE].
 * @property byteSink the raw-byte face of the same idea: the
 *   chunks exactly as the boundary reads them — the byte level
 *   where a newline-less prompt arrives (the line sink waits for
 *   the split); null: the byte level unheard. Mute under
 *   [CaptureMode.NONE] like its line sibling.
 * @property timeout how long the child may run before the
 *   boundary destroys it (the graceful kill first, the forceful
 *   escalation after — the graceful face is the JVM's and the
 *   POSIX's own: Windows knows no gentler kill than
 *   `TerminateProcess`); null: the child runs as long as it wants.
 * @property killOnParentDeath the orphan fence: the child's life
 *   rides the parent's — when rhea's own process dies, the
 *   boundary's machinery destroys the child (the JVM's shutdown
 *   hook, Linux's `PR_SET_PDEATHSIG`, the Win32 job's
 *   kill-on-close). True by default: a dead parent never leaves a
 *   live child. False: the daemon's door — the child may outlive
 *   the run. The macOS tier answers nothing here (the kernel
 *   offers no face); the fence is the documented no-op there.
 */
public data class RunOptions(
    public val capture: CaptureMode = CaptureMode.SEPARATE,
    public val stdin: Stdin = Stdin.Inherit,
    public val directory: String? = null,
    public val environment: Map<String, String> = emptyMap(),
    public val sink: Sink? = null,
    public val byteSink: ByteSink? = null,
    public val timeout: Duration? = null,
    public val killOnParentDeath: Boolean = true,
)
