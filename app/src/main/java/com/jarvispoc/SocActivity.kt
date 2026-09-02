package com.jarvispoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jarvispoc.flows.CyberSecApiClient
import org.json.JSONObject

class SocActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SocApp()
                }
            }
        }
    }
}

@Composable
fun SocApp() {
    var currentScreen by remember { mutableStateOf("Dashboard") }
    var incidents by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedIncident by remember { mutableStateOf<JSONObject?>(null) }
    
    var incidentState by remember { mutableStateOf("OPEN") }
    
    var chaosMode by remember { mutableStateOf(false) }
    var escalateFailures by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        incidents = CyberSecApiClient.getIncidents()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("SOC SIMULATOR", style = MaterialTheme.typography.headlineMedium)
            Row {
                Text("Chaos Mode", modifier = Modifier.padding(end = 8.dp, top = 12.dp))
                Switch(checked = chaosMode, onCheckedChange = { 
                    chaosMode = it
                    if (it) escalateFailures = 1 else escalateFailures = 0
                }, modifier = Modifier.semantics { contentDescription = "chaos_toggle" })
            }
        }
        Spacer(Modifier.height(16.dp))

        when (currentScreen) {
            "Dashboard" -> {
                Text("ALERTS", modifier = Modifier.semantics { contentDescription = "screen_title" })
                Spacer(Modifier.height(8.dp))
                
                Button(onClick = { 
                    scope.launch { incidents = CyberSecApiClient.getIncidents() }
                }) {
                    Text("Refresh Backend State")
                }
                Spacer(Modifier.height(8.dp))
                
                val displayList = if (chaosMode) incidents.reversed() else incidents
                
                LazyColumn {
                    items(displayList) { inc ->
                        val incId = inc.optString("incident_id")
                        val sev = inc.optString("severity").uppercase()
                        Button(onClick = { 
                            selectedIncident = inc
                            currentScreen = "IncidentDetail" 
                        }, modifier = Modifier.semantics { 
                            contentDescription = "alert_item" 
                            text = androidx.compose.ui.text.AnnotatedString("Severity: $sev, ID: $incId")
                        }) {
                            Text("Incident $incId - $sev RISK")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            "IncidentDetail" -> {
                val inc = selectedIncident
                if (inc != null) {
                    val incId = inc.optString("incident_id")
                    val sev = inc.optString("severity").uppercase()
                    Text("Alert Details", modifier = Modifier.semantics { contentDescription = "alert_detail_title" })
                    Text("Incident ID: $incId", modifier = Modifier.semantics { 
                        contentDescription = "incident_id_label"
                        text = androidx.compose.ui.text.AnnotatedString(incId)
                    })
                    Text("Severity: $sev", modifier = Modifier.semantics { 
                        contentDescription = "alert_severity_label"
                        text = androidx.compose.ui.text.AnnotatedString(sev)
                    })
                    
                    Spacer(Modifier.height(16.dp))
                    Text("Status: $incidentState", modifier = Modifier.semantics { 
                        contentDescription = "incident_status_label" 
                        text = androidx.compose.ui.text.AnnotatedString(incidentState)
                    })
                    Spacer(Modifier.height(16.dp))
                    
                    Button(onClick = { 
                        scope.launch {
                            if (chaosMode && escalateFailures > 0) {
                                escalateFailures--
                                // Fails silently in chaos mode
                            } else {
                                if (chaosMode) delay(500)
                                incidentState = "ESCALATED"
                            }
                        }
                    }, modifier = Modifier.semantics { contentDescription = "escalate_button" }) {
                        Text("ESCALATE INCIDENT")
                    }
                }
            }
        }
        
        Spacer(Modifier.weight(1f))
        Button(onClick = { 
            currentScreen = "Dashboard"
            incidentState = "OPEN"
            if (chaosMode) escalateFailures = 1
            scope.launch { incidents = CyberSecApiClient.getIncidents() }
        }, modifier = Modifier.semantics { contentDescription = "back_button" }) {
            Text("Back to Dashboard (Reset)")
        }
    }
}
