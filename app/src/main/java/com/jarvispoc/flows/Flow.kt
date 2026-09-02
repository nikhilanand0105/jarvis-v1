package com.jarvispoc.flows

import com.jarvispoc.core.FlowResult
import com.jarvispoc.service.ActionExecutor

/**
 * A scripted sequence of steps against one app.
 *
 * Deliberately not an LLM planner. The open question this POC exists to answer
 * is "can we reliably find and tap things in Amazon and Instagram", and a
 * deterministic script isolates that question instead of tangling it with
 * planner quality. The interface is here so a planner can be dropped in later
 * without touching [ActionExecutor] or the service.
 */
interface Flow {

    val name: String

    /**
     * @param autoConfirm when false, the flow stops at its irreversible step
     *   (placing an order, sharing a post) and returns [FlowResult.AwaitingUser].
     */
    suspend fun run(x: ActionExecutor, autoConfirm: Boolean): FlowResult
}
