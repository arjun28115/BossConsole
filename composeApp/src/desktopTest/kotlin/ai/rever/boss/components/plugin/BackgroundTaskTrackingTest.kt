package ai.rever.boss.components.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A handle the provider never releases is invisible through `BackgroundTaskProvider`:
 * `getRunningTasks` filters it out because it is no longer active, and `cancelAll` skips it for the
 * same reason. The only symptom is `activeTasks` growing for the lifetime of the window, which is
 * why these assert on the tracked count rather than on anything the interface reports.
 */
class BackgroundTaskTrackingTest {
    /**
     * The handle was stored after `scope.launch`, and the release was a `finally` inside the
     * coroutine. A task that reaches the end of its body before the launching thread stores the
     * handle therefore removed a key that was not there yet, and the handle that landed afterwards
     * was never released.
     *
     * `Dispatchers.Unconfined` makes that ordering the only possible one rather than a race: the
     * body runs inline on `launch` and this task never suspends, so it is complete before
     * `launchTask` returns.
     *
     * In production the scope is `Dispatchers.Main`. A caller already on the UI thread cannot hit
     * this, because the body is queued behind the rest of the current frame. `launchTask` is
     * plugin-facing and documents no thread requirement, though, and nothing in this repository
     * calls it: every caller is a plugin, running on a thread of its own choosing. A plugin calling
     * it off the UI thread gets exactly this interleaving.
     */
    @Test
    fun `a task that finishes before its handle is stored does not leak the handle`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val provider = DefaultBackgroundTaskProvider(scope)

        provider.launchTask("probe") { }

        assertEquals(
            0,
            provider.trackedTaskCount(),
            "a finished task must not leave a handle behind; those are invisible and unbounded",
        )
        scope.cancel()
    }

    /** The ordinary path: tracked while it runs, released when it finishes. */
    @Test
    fun `a task that suspends is tracked until it completes`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val provider = DefaultBackgroundTaskProvider(scope)
        val gate = CompletableDeferred<Unit>()

        provider.launchTask("worker") { gate.await() }
        assertEquals(1, provider.trackedTaskCount(), "a suspended task is still a running task")

        // Unconfined resumes the continuation inline, so the job is complete when this returns.
        gate.complete(Unit)
        assertEquals(0, provider.trackedTaskCount(), "finishing releases the handle")

        scope.cancel()
    }

    /** Cancellation is a completion too, so it must release the handle by the same route. */
    @Test
    fun `a cancelled task releases its handle`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val provider = DefaultBackgroundTaskProvider(scope)
        val gate = CompletableDeferred<Unit>()

        val handle = provider.launchTask("worker") { gate.await() }
        assertEquals(1, provider.trackedTaskCount())

        handle?.cancel()
        assertEquals(0, provider.trackedTaskCount(), "cancelling releases the handle")

        scope.cancel()
    }
}
