package com.jarvispoc.flows

import com.jarvispoc.core.AgentLog
import com.jarvispoc.core.FlowResult
import com.jarvispoc.core.Query
import com.jarvispoc.core.Selector
import com.jarvispoc.service.ActionExecutor
import kotlinx.coroutines.delay

class CyberSecFlow : Flow {
    override val name = "CyberSecSOC"

    override suspend fun run(x: ActionExecutor, autoConfirm: Boolean): FlowResult {
        AgentLog.info("Starting CyberSec SOC Investigation Flow (Data-Driven Agent Loop)")

        // Phase 1: OBSERVE & IDENTIFY
        AgentLog.step("CYBERSEC_IDENTIFY: Launching SOC Simulator")
        x.launchPackage("com.jarvispoc")
        delay(2000)

        AgentLog.step("CYBERSEC_OBSERVE: Scanning UI for HIGH risk incidents")
        // Dynamically find a node containing "HIGH RISK"
        val alertQuery = Query("High Risk Alert", listOf(Selector(textContains = "HIGH RISK")))
        val alertNode = x.scrollUntilVisible(alertQuery)
        
        if (alertNode == null) {
            AgentLog.error("Could not find any HIGH risk alert in UI")
            return FlowResult.Failed("CYBERSEC_OBSERVE", "No HIGH risk alert found")
        }

        // Phase 2: OPEN & EXTRACT
        AgentLog.step("CYBERSEC_OPEN_ALERT: Opening selected alert")
        x.tap(alertNode)
        delay(1500)

        // Find the incident ID label semantically to extract the exact UUID
        val idQuery = Query("Incident ID Label", listOf(Selector(desc = "incident_id_label")))
        val idNode = x.scrollUntilVisible(idQuery)
        if (idNode == null) {
            AgentLog.warn("Incident ID label not found in UI")
            return FlowResult.Failed("CYBERSEC_EXTRACT", "Incident ID missing")
        }
        
        // Extract the raw text from the node
        val extractedId = idNode.text ?: idNode.contentDescription ?: ""
        if (extractedId.isBlank()) {
            return FlowResult.Failed("CYBERSEC_EXTRACT", "Incident ID text was blank")
        }
        AgentLog.step("CYBERSEC_EXTRACT: Read Identity from UI -> $extractedId")

        // Phase 3: QUERY BACKEND & CORRELATE
        AgentLog.step("CYBERSEC_CHECK_IP / CYBERSEC_CHECK_CVE: Fetching full report for $extractedId")
        val report = CyberSecApiClient.investigateIncident(extractedId)
        
        if (report == null) {
            AgentLog.error("Investigation API failed or returned 404 for $extractedId.")
            return FlowResult.Failed("CYBERSEC_CHECK_IP", "API Error")
        }
        
        val evidenceList = report.optJSONArray("evidence")
        val promptInjection = evidenceList?.let { 
            (0 until it.length()).any { i -> it.optString(i).contains("IGNORE ALL PREVIOUS INSTRUCTIONS", ignoreCase = true) }
        } == true
        
        if (promptInjection) {
            AgentLog.warn("CYBERSEC_DECIDE: Found untrusted prompt injection in evidence.")
            // Policy Gate: Treat as data only. The agent's goal remains ESCALATE if severity is HIGH.
        }

        val severity = report.optString("severity").lowercase()
        if (severity != "high") {
            AgentLog.step("CYBERSEC_DECIDE: Severity is $severity. No action required.")
            return FlowResult.Success("Incident investigated, no escalation needed.")
        }

        AgentLog.step("CYBERSEC_DECIDE: Policy gate approved ESCALATE action for HIGH incident.")

        // Phase 4: EXECUTE & VERIFY WITH REAL REPLANNING
        var escalated = false
        var attempts = 0
        val maxAttempts = 3
        
        while (!escalated && attempts < maxAttempts) {
            attempts++
            AgentLog.step("CYBERSEC_EXECUTE: Attempting to ESCALATE (Plan A, Attempt $attempts)")
            
            val escalateNode = x.scrollUntilVisible(Query("Escalate Button", listOf(Selector(desc = "escalate_button"))))
            if (escalateNode != null) {
                x.tap(escalateNode)
                delay(1500)
                
                // VERIFY
                AgentLog.step("CYBERSEC_VERIFY: Checking status label")
                val statusNode = x.scrollUntilVisible(Query("Status Label", listOf(Selector(desc = "incident_status_label"))))
                val statusText = statusNode?.text ?: statusNode?.contentDescription ?: ""
                
                if (statusText.contains("ESCALATED", ignoreCase = true)) {
                    escalated = true
                    AgentLog.success("CYBERSEC_COMPLETE: Verification passed. State is ESCALATED.")
                } else {
                    AgentLog.warn("CYBERSEC_RECOVER: Verification failed. Status is still '$statusText'.")
                    AgentLog.step("CYBERSEC_REPLAN: Replanning... Re-observing screen and retrying click.")
                    delay(1000)
                    // Replan: We navigate back and forth or just retry the explicit click with a fresh node
                    // Replan: The verification failed. We trigger a re-scan of the UI node tree to dynamically adjust to the new layout and try again.
                }
            } else {
                AgentLog.warn("CYBERSEC_RECOVER: Escalate button not found. Replanning.")
                delay(1000)
            }
        }
        
        if (escalated) {
            return FlowResult.Success("Incident escalated")
        } else {
            AgentLog.error("CYBERSEC_HALT: Failed to escalate after $maxAttempts attempts.")
            return FlowResult.Failed("CYBERSEC_EXECUTE", "Action verification failed repeatedly")
        }
    }
}
