// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The run's suspending face: the same spawn, the same verdict,
 * the blocking ride parked on a worker thread that can afford it
 * (the JVM's IO pool, the natives' default pool). The name is
 * `await` everywhere: the blocking [Rhea.run] and the suspending
 * [Rhea.await] never blur into an overload pair.
 *
 * The cancellation's own terms: a cancelled await returns control
 * at once, and the child's life stays with its own options (the
 * timeout's ladder, the orphan fence). The conversation whose
 * cancellation must kill the child is the session's own
 * ([Session.await]). The pool's ceiling is the pool's own: the
 * JVM's IO pool (64 threads by default), the natives' default
 * pool (the cores' count) — that many awaits park that many
 * threads.
 */
public suspend fun Rhea.await(
    executable: String,
    args: List<String> = emptyList(),
    options: RunOptions = RunOptions(),
): ProcessResult =
    withContext(awaitDispatcher) {
        run(executable, args, options)
    }

/**
 * The conversation's suspending face: the verdict as a suspension
 * point, the blocking wait parked on a worker thread that can
 * afford it. The cancellation's law is the session's own: a
 * cancelled await pulls the plug ([Session.destroy]) — the child's
 * death is the cancellation's doing, the worker's parked wait
 * answers into the void (the resumed verdict is discarded by the
 * cancelled continuation). The hook arms when the worker's hand
 * arrives (the dispatch's own moment): a cancel racing the
 * dispatch itself never arms it, and the session stays the
 * caller's own hand — [Session.destroy] is always theirs to call.
 */
public suspend fun Session.await(): ProcessResult =
    withContext(awaitDispatcher) {
        suspendCancellableCoroutine { continuation ->
            // the cancellation's hook: the plug's pull at the
            // cancel's own moment — the child's death unblocks the
            // worker's wait, the resumed verdict rides nowhere
            continuation.invokeOnCancellation { destroy() }
            // the blocking wait's ride: this block runs on the
            // dispatcher's own worker (the withContext's dispatch),
            // the park costs no caller thread — the stdlib's own
            // resumeWith answers the mire of the coroutines'
            // overload faces
            continuation.resumeWith(Result.success(waitFor()))
        }
    }
