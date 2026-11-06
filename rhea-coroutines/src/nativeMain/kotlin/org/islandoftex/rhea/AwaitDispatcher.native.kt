// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The natives' ride: the default pool — the K/N worker pool that
 * already runs the drains' neighbors.
 */
internal actual val awaitDispatcher: CoroutineDispatcher = Dispatchers.Default
