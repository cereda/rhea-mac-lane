// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The JVM's ride: the IO pool — the threads that exist for the
 * blocking calls' sake.
 */
internal actual val awaitDispatcher: CoroutineDispatcher = Dispatchers.IO
