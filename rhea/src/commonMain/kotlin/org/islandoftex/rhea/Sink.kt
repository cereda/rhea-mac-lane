// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The line's stream — the sink's attribution, honest to the
 * capture mode: `OUT` and `ERR` under the separate capture (the
 * drains know their pipes), [UNKNOWN] under the merged one (the
 * pipe erased the attribution).
 */
public enum class Stream {
    /** The child's standard output. */
    OUT,

    /** The child's standard error. */
    ERR,

    /** The attribution's erased face: the merged capture's pipe. */
    UNKNOWN,
}

/**
 * The consumer of the child's lines while they stream: a logger,
 * the console, a file — anything that eats lines. The port is
 * dependency-free on purpose: the consumer's logger adapts in one
 * lambda.
 *
 * The lines arrive with their [Stream] attribution — the
 * level-routing's floor (`stderr` lines to the warnings, say) —
 * and the [Stream.UNKNOWN] under the merged capture keeps the
 * port honest: the pipe erased the attribution, the sink says so.
 * Under the raw capture ([CaptureMode.NONE]) no pipes exist: the
 * sink hears nothing.
 *
 * The throw's contract: the sinks eat lines, never throw — a
 * throwing sink is the caller's own machinery's funeral (the
 * uncaught exception's own terms: the drain thread dies with the
 * host's process on the native tiers, the pump thread dies
 * quietly on the JVM). The ears serialize: the sink hears one
 * line at a time — the two pipes' concurrent drains ride the
 * tiers' own locks, the caller's logger needs no lock of its own.
 */
public fun interface Sink {
    /**
     * Accepts one decoded [line] from [stream].
     */
    public fun accept(
        line: String,
        stream: Stream,
    )
}
