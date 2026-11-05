// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The child's stdin policy.
 *
 * [Inherit] hands the parent's own stdin to the child — the
 * interactive tools' floor (a prompt reaches the user, the user's
 * answer reaches the child); free on the POSIX tier, one redirect
 * on the JVM. [Closed] isolates: the child reads an immediate end
 * of stream. [From] pumps the given bytes into the child's stdin
 * and closes it — the feed-the-tool face.
 */
public sealed class Stdin {
    /** The child reads the parent's stdin. */
    public object Inherit : Stdin()

    /** The child reads an immediate end of stream. */
    public object Closed : Stdin()

    /** The child reads [bytes], then an end of stream. */
    public class From(
        public val bytes: ByteArray,
    ) : Stdin()
}
