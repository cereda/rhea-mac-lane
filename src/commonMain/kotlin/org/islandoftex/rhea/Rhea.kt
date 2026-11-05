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
     * the capture shaped by the [options].
     * @throws ProcessException when the boundary itself fails (a
     * blank executable, a refused spawn).
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
     * the capture shaped by the [options].
     * @throws ProcessException when the boundary itself fails.
     */
    public fun run(
        executable: String,
        vararg args: String,
        options: RunOptions = RunOptions(),
    ): ProcessResult = run(executable, args.toList(), options)
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
