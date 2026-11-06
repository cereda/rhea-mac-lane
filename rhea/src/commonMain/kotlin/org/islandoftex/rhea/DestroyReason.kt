// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * Why the boundary destroyed a child.
 */
public enum class DestroyReason {
    /** The run exceeded the caller's timeout. */
    TIMEOUT,

    /** The session's caller pulled the plug ([Session.destroy]). */
    DESTROYED,
}
