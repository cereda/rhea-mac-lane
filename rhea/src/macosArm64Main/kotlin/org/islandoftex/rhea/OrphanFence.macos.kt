// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The fence's macOS face: the documented no-op — the kernel
 * offers no parent-death answer (no `prctl`, no portable face
 * that survives the parent's own death). A child orphaned here
 * is the platform's own silence, and the [RunOptions]'
 * `killOnParentDeath` flag answers nothing on this tier.
 */
internal actual fun armParentDeathFence() = Unit
