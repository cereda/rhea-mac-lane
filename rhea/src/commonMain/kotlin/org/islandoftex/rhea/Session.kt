// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The conversation's face: the child chosen by [Rhea.start] with
 * its stdin a pipe the caller's own hand drives while the child
 * lives. The verbs: [write] (text and bytes, the flushed face),
 * [endOfInput] (the child's EOF), [destroy] (the boundary's
 * ladder, speaking [DestroyReason.DESTROYED]). [waitFor] carries
 * the verdict home — the same shapes `Rhea.run` speaks, the
 * capture and the sinks riding live the whole way.
 *
 * The spawn's rules stay `Rhea.run`'s, one exception: the stdin is
 * ALWAYS a pipe — the [RunOptions]' `stdin` policy is not honored
 * (the pipe's other end is this session's hand).
 *
 * The lifecycle's law: the caller owns the conversation — a
 * session abandoned without [waitFor] keeps the child alive (the
 * orphan fence protects the *process's* death, never the
 * caller's forgetfulness), and the conversation's machinery
 * (the keeper's park, the drains) rides the child's own lifetime.
 * The conversation answers one hand at a time: the verbs' checks
 * (a write after a spoken end, a second end) are the sequential
 * face. The verdict alone welcomes the second hand — the cache
 * and the re-entry gate answer a concurrent [waitFor] with the
 * first hand's own verdict, whatever tier it rode. The verdict
 * reclaims the conversation's faces too: the drained streams
 * close, and the unspoken stdin's own (a late write answers
 * [ProcessException] — the child's gone).
 *
 * The conversation, in the small — the tool stops at the `?`
 * prompt (a prompt riding no newline: the byte sink's own face
 * hears it), and the caller's hand stands in for the keyboard:
 *
 * ```
 * val session = Rhea.start("pdflatex", "-errorstopmode", "doc.tex")
 * session.write("x\n")   // the answer to the prompt
 * session.endOfInput()   // the child's stdin reads its EOF
 * val verdict = session.waitFor()
 * ```
 */
public class Session
    internal constructor(
        private val driver: SessionDriver,
    ) {
        /**
         * Writes [text] into the child's stdin (the flushed face).
         * @throws IllegalStateException after a spoken end.
         * @throws ProcessException when the child's gone (the
         *   pipe's end closed — the write's face rides the
         *   boundary's own vocabulary on every tier).
         */
        public fun write(text: String): Session = apply { driver.write(text.encodeToByteArray()) }

        /**
         * Writes [bytes] into the child's stdin (the flushed face).
         * @throws IllegalStateException after a spoken end.
         * @throws ProcessException when the child's gone (the
         *   pipe's end closed — the write's face rides the
         *   boundary's own vocabulary on every tier).
         */
        public fun write(bytes: ByteArray): Session = apply { driver.write(bytes) }

        /**
         * Speaks the end: the child's stdin reads its EOF.
         * @throws IllegalStateException when the end already spoke.
         */
        public fun endOfInput(): Session = apply { driver.endOfInput() }

        /**
         * Pulls the plug: the destroyer's ladder rides the boundary's
         * own terms (graceful kill, the grace, the forceful
         * escalation); the verdict speaks [DestroyReason.DESTROYED].
         * An already-exited child answers nothing. The grace's
         * patience is the caller's own hand on the POSIX tier (the
         * call blocks through it); the JVM's and the Windows' tiers
         * spawn their patience and answer at once.
         */
        public fun destroy(): Session = apply { driver.destroy() }

        /**
         * The child's pid: the platform's own number — the JVM's
         * `Process.pid()`, the fork's pid, the Win32 process id.
         */
        public val pid: Long
            get() = driver.pid

        /**
         * Waits for the verdict: the drains' capture — the sinks'
         * ears the whole session long — reaches home here. The
         * single-shot face: the verdict caches, and a re-ask
         * answers the cached one.
         */
        public fun waitFor(): ProcessResult = driver.waitFor()
    }

/**
 * The platform's conversation machinery: the actuals' contract.
 */
internal interface SessionDriver {
    /** The child's pid (the platform's own number). */
    public val pid: Long

    /**
     * The bytes feed the child's stdin (the flushed face).
     * @throws ProcessException when the child's gone.
     */
    public fun write(bytes: ByteArray)

    /** The end of the stdin's stream. */
    public fun endOfInput()

    /** The destroy ladder, ruled [DestroyReason.DESTROYED]. */
    public fun destroy()

    /** The verdict. */
    public fun waitFor(): ProcessResult
}

/**
 * The ruling's atomic vocabulary: the int whose faces are none,
 * the timeout's, the destroyer's (the tiers' atomic int speaks
 * these alone — the first ruler wins).
 */
internal const val RULED_NONE = 0
internal const val RULED_TIMEOUT = 1
internal const val RULED_DESTROYED = 2

/** The ruling code's reason (null: nobody ruled). */
internal fun reasonOf(code: Int): DestroyReason? =
    when (code) {
        RULED_NONE -> null
        RULED_TIMEOUT -> DestroyReason.TIMEOUT
        else -> DestroyReason.DESTROYED
    }

/**
 * The session's spawn: the actuals' contract — the stdin's pipe
 * always wired, the drains live from the spawn's moment.
 */
internal expect fun startSession(
    executable: String,
    args: List<String>,
    options: RunOptions,
): Session
