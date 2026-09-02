package com.jarvispoc.core

/**
 * Outcome of a scripted flow.
 *
 * [AwaitingUser] is a first-class success, not a failure: it is what the
 * Amazon flow returns when it reaches the final confirm screen and refuses to
 * spend money on its own.
 */
sealed interface FlowResult {

    data class Success(val message: String) : FlowResult

    /** Flow completed its automated part and deliberately handed back control. */
    data class AwaitingUser(val message: String) : FlowResult

    data class Failed(val step: String, val reason: String) : FlowResult

    val summary: String
        get() = when (this) {
            is Success -> "OK — $message"
            is AwaitingUser -> "PAUSED — $message"
            is Failed -> "FAILED at '$step' — $reason"
        }
}
