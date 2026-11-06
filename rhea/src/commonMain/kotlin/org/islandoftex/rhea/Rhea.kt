// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The facade: one face, every platform. The machinery hides in the
 * expect/actual tier (the JVM's `ProcessBuilder`, POSIX
 * `fork`/`execvp`, the Win32 `CreateProcessW`) — the caller speaks
 * an executable and its arguments, and reads verdicts.
 *
 * The library never parses: there is no string-command face, no
 * splitting, no shell — the executable and the args are the argv,
 * spoken separately and typed.
 */
public object Rhea {
    /**
     * Runs an [executable] with [args] and waits for the verdict.
     * @return the [ProcessResult.Completed] with the exit code and
     *   the capture shaped by the [options] — or
     *   [ProcessResult.Destroyed] when the [RunOptions]' timeout's
     *   ladder spoke first (the partial capture survives).
     * @throws ProcessException when the boundary itself fails (a
     * blank executable, a refused spawn — the uniform vocabulary
     * on every tier: the POSIX tier's exec-refusal rides the
     * internal 254 marker, decoded into the story before it
     * reaches the caller).
     */
    public fun run(
        executable: String,
        args: List<String> = emptyList(),
        options: RunOptions = RunOptions(),
    ): ProcessResult {
        if (executable.isBlank()) {
            throw ProcessException("The executable is blank: nothing to run.")
        }
        return execute(executable, args, options)
    }

    /**
     * Runs an [executable] with the [args] words and waits for the
     * verdict.
     * @return the [ProcessResult.Completed] with the exit code and
     *   the capture shaped by the [options] — or
     *   [ProcessResult.Destroyed] when the timeout's ladder spoke
     *   first.
     * @throws ProcessException when the boundary itself fails
     *   (the tiers' own faces — see [run], the list overload).
     */
    public fun run(
        executable: String,
        vararg args: String,
        options: RunOptions = RunOptions(),
    ): ProcessResult = run(executable, args.toList(), options)

    /**
     * Starts the conversation: [executable] with [args] runs while
     * the [Session]'s hand feeds it. The stdin is ALWAYS a pipe —
     * the [RunOptions]' `stdin` policy is not honored (the pipe's
     * other end belongs to the session's writes and its
     * [Session.endOfInput]); every other policy composes exactly
     * as in [run]: the capture's face, the sinks, the timeout's
     * ladder.
     *
     * The child's streams ride their drains from the spawn's own
     * moment — the sinks hear the conversation live. The verdict
     * speaks at [Session.waitFor]; the caller's own destroyer
     * ([Session.destroy]) speaks [DestroyReason.DESTROYED].
     * @throws ProcessException when the boundary itself fails.
     */
    public fun start(
        executable: String,
        args: List<String> = emptyList(),
        options: RunOptions = RunOptions(),
    ): Session {
        if (executable.isBlank()) {
            throw ProcessException("The executable is blank: nothing to run.")
        }
        return startSession(executable, args, options)
    }

    /**
     * Starts the conversation with [executable] and the [args]
     * words — the vararg face of [start].
     */
    public fun start(
        executable: String,
        vararg args: String,
        options: RunOptions = RunOptions(),
    ): Session = start(executable, args.toList(), options)
}

/**
 * The platform's machinery: the spawn, the capture, the reaping —
 * the actuals' shared contract.
 */
internal expect fun execute(
    executable: String,
    args: List<String>,
    options: RunOptions,
): ProcessResult
