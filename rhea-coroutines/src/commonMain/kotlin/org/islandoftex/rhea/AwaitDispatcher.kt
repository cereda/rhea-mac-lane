// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The blocking wait's ride: the worker pool whose threads can
 * afford the park. The JVM's own face is the IO pool (the
 * blocking call's home); the natives' is the default pool.
 * Internal on purpose: the public surface is the await pair
 * alone.
 */
internal expect val awaitDispatcher: CoroutineDispatcher
