// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The child's stdin policy.
 *
 * [Inherit] hands the parent's own stdin to the child — the
 * interactive tools' floor (a prompt reaches the user, the user's
 * answer reaches the child); the parent's real stream rides on
 * every tier. [Closed] isolates: the child reads an immediate end
 * of stream. [From] pumps the given bytes into the child's stdin
 * and closes it — the feed-the-tool face.
 */
public sealed class Stdin {
    /** The child reads the parent's stdin. */
    public object Inherit : Stdin()

    /**
     * The child reads an immediate end of stream. The Windows'
     * own face is the unwired one: no stdin handle exists at all
     * (the tool's read answers its own end — the no-hang's own
     * proof); the JVM's and the POSIX's faces ride the null
     * device's clean EOF.
     */
    public object Closed : Stdin()

    /**
     * The child reads [bytes], then an end of stream.
     *
     * @property bytes the bytes the child's stdin drinks before
     *   the pipe closes.
     */
    public class From(
        public val bytes: ByteArray,
    ) : Stdin()
}
