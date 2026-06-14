/**
 * Lightweight bridge for observing Kotlin [Flow]s from platform code that does
 * not natively support coroutines (e.g. Swift/Obj-C on iOS).
 *
 * iOS callers create a [FlowObserver], call [FlowObserver.observe] with a
 * callback for each [StateFlow] they care about, and call [FlowObserver.clear]
 * when the owning view controller is deallocated.
 *
 * @see se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel
 */
package se.soderbjorn.termtastic.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects one or more Kotlin [Flow]s on the main dispatcher and forwards
 * every emission to a platform callback. Designed for iOS/Swift interop where
 * `Flow.collect` is not directly callable.
 *
 * Usage:
 * ```
 * val observer = FlowObserver()
 * observer.observe(viewModel.stateFlow) { state -> updateUI(state) }
 * // later, when the screen is torn down:
 * observer.clear()
 * ```
 */
class FlowObserver {
    private val scope: CoroutineScope = MainScope()

    /**
     * Start collecting [flow] on the main thread, invoking [onChange] for every
     * emitted value. Multiple flows can be observed concurrently through the
     * same [FlowObserver] instance.
     *
     * @param T        the element type of the flow.
     * @param flow     the [Flow] to observe.
     * @param onChange callback invoked on every emission.
     */
    fun <T> observe(flow: Flow<T>, onChange: (T) -> Unit) {
        scope.launch {
            flow.collect { value ->
                onChange(value)
            }
        }
    }

    /**
     * Cancel all active flow collections started by [observe]. After this call
     * the observer is no longer usable.
     */
    fun clear() {
        scope.cancel()
    }
}
