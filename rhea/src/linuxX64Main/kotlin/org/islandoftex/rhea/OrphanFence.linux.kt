// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.islandoftex.rhea

import platform.linux.PR_SET_PDEATHSIG
import platform.linux.prctl
import platform.posix.SIGKILL
import platform.posix._exit
import platform.posix.getppid

/**
 * The fence's Linux face: the kernel's own answer — the parent
 * THREAD's death delivers the SIGKILL, instant and unconditional
 * (the keeper's park keeps the thread alive for the session's
 * sake; the one-shot run's caller blocks in the wait anyway).
 */
internal actual fun armParentDeathFence() {
    prctl(PR_SET_PDEATHSIG, SIGKILL)

    // the fork-and-fence race: the parent died between the fork
    // and the arm — the child's already an orphan; exit now (the
    // reparenting's face: pid 1)
    if (getppid() == 1) {
        _exit(1)
    }
}
