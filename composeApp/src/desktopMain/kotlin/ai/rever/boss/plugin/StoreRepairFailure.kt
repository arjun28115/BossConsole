package ai.rever.boss.plugin

import kotlinx.coroutines.CancellationException

/** Transport failures are repairable; cancellation must never enqueue work for another channel. */
internal fun queueStoreRepairAfterGitHubFailure(
    failure: Exception,
    enqueue: (String) -> Unit,
) {
    if (failure is CancellationException) throw failure
    // The exception type identifies DNS, TLS or timeout failures without copying URL credentials.
    enqueue("GitHub request failed (${failure.javaClass.simpleName})")
}
