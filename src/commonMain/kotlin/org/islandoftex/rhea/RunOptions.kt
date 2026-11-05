// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The run's modifiers. The unset ones stay out of the machinery.
 *
 * @property capture how the child's streams reach the result —
 *   [CaptureMode.SEPARATE] (the default) keeps the attribution,
 *   [CaptureMode.MERGED] preserves the kernel's interleaving.
 * @property stdin the child's stdin policy —
 *   [StdinPolicy.INHERIT] (the default) hands the parent's stream
 *   over; [StdinPolicy.CLOSED] isolates.
 */
public data class RunOptions(
    public val capture: CaptureMode = CaptureMode.SEPARATE,
    public val stdin: StdinPolicy = StdinPolicy.INHERIT,
)
