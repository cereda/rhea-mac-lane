// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The boundary's own failure: the spawn refused (the command
 * missing, the platform denying the call), the pipes broken. As
 * opposed to the tool *running and answering* — any exit code is
 * the [ProcessResult]'s vocabulary, not this exception's.
 *
 * @property output the capture so far when the failure fires
 *   mid-conversation — the session's broken pipe carries the
 *   story the child told before the break. The spawn-refusal
 *   faces predate any output: the child never ran, the property
 *   answers "".
 */
public class ProcessException(
    message: String,
    cause: Throwable? = null,
    public val output: String = "",
) : RuntimeException(message, cause)
