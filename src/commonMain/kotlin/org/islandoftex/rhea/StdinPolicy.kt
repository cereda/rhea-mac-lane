// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The child's stdin policy.
 *
 * [INHERIT] hands the parent's own stdin to the child — the
 * interactive tools' floor (a prompt reaches the user, the user's
 * answer reaches the child); free on the POSIX tier, one redirect
 * on the JVM. [CLOSED] isolates: the child reads an immediate end
 * of stream.
 */
public enum class StdinPolicy {
    /** The child reads the parent's stdin. */
    INHERIT,

    /** The child reads an immediate end of stream. */
    CLOSED,
}
