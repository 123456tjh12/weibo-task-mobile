package com.tjh.weibotask

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class CloudTask(val id: String)
data class PairResult(val deviceId: String, val deviceToken: String)

object CloudClient {
    fun pair(cloudUrl: String, code: String, deviceName: String): PairResult {
        val body = JSONObject()
            .put("code", code)
            .put("name", deviceName)
        val json = request(cloudUrl.trimEnd('/') + "/api/agent/pair", "POST", null, body)
        return PairResult(json.getString("deviceId"), json.getString("deviceToken"))
    }

    fun heartbeat(): JSONObject {
        return request(Prefs.cloudUrl + "/api/agent/heartbeat", "POST", Prefs.deviceToken, JSONObject())
    }

    fun fetchTask(): CloudTask? {
        val json = request(Prefs.cloudUrl + "/api/agent/tasks", "GET", Prefs.deviceToken, null)
        val task = json.optJSONObject("task") ?: return null
        return CloudTask(task.getString("id"))
    }

    fun reportResult(
        taskId: String,
        status: String,
        riskScore: Int,
        errorCode: String,
        pauseReason: String,
        consecutiveFailures: Int,
        summary: JSONObject
    ) {
        val body = JSONObject()
            .put("status", status)
            .put("riskScore", riskScore)
            .put("errorCode", errorCode)
            .put("pauseReason", pauseReason)
            .put("consecutiveFailures", consecutiveFailures)
            .put("summary", summary)
        request(Prefs.cloudUrl + "/api/agent/tasks/" + taskId + "/result", "POST", Prefs.deviceToken, body)
    }

    private fun request(urlText: String, method: String, deviceToken: String?, body: JSONObject?): JSONObject {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (!deviceToken.isNullOrBlank()) {
            connection.setRequestProperty("X-Device-Token", deviceToken)
        }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (code !in 200..299) {
            throw IllegalStateException(json.optString("error", "HTTP $code"))
        }
        return json
    }
}
