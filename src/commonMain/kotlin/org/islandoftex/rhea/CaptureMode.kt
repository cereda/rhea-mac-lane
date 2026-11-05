// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The capture mode of a run: how the child's streams reach the
 * result.
 *
 * The default, [SEPARATE], keeps the attribution — the result's
 * `stdout` and `stderr` fields answer which stream said what, and
 * the `output` composes them in the drains' arrival order (a
 * near-true merge; the kernel's order is unrecoverable from two
 * buffers).
 *
 * [MERGED] joins the streams at the pipe level — the kernel's own
 * interleaving, the LaTeX log's artifact — at the attribution's
 * price: the fields answer null, the source is gone forever.
 */
public enum class CaptureMode {
    /** The attributed capture: two pipes, concurrent drains. */
    SEPARATE,

    /** The pipe-level join: one pipe, the kernel's true order. */
    MERGED,
}
