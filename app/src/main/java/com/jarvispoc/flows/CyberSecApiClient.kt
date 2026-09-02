package com.jarvispoc.flows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.jarvispoc.core.AgentLog

object CyberSecApiClient {
    private const val BASE_URL = "http://10.0.2.2:8000"

    suspend fun getIncidents(): List<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/incidents")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(response)
                val list = mutableListOf<JSONObject>()
                for (i in 0 until array.length()) {
                    list.add(array.getJSONObject(i))
                }
                list
            } else {
                AgentLog.error("Failed to get incidents: HTTP ${conn.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            AgentLog.error("Error fetching incidents: ${e.message}")
            emptyList()
        }
    }

    suspend fun investigateIncident(incidentId: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/investigate/$incidentId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response)
            } else {
                AgentLog.error("Failed to investigate incident: HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            AgentLog.error("Error investigating incident: ${e.message}")
            null
        }
    }
}
