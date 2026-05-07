package com.pomoremote.timer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SyncManager {

    private val gson = Gson()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SYNC_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .readTimeout(SYNC_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .writeTimeout(SYNC_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .build()

    private var offlineSince: Long = 0

    fun setOffline() {
        if (offlineSince == 0L) {
            offlineSince = System.currentTimeMillis() / 1000
        }
    }

    fun clearOffline() {
        offlineSince = 0
    }

    suspend fun syncNow(ip: String, port: Int, localState: TimerState): TimerState = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ip:$port/api/sync"
            val req = SyncRequest()
            req.state = localState
            req.offline_since = offlineSince

            val json = gson.toJson(req)
            val body = json.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val responseBody = response.body!!.string()
                    val jsonResponse = JsonParser.parseString(responseBody).asJsonObject

                    if (jsonResponse.has("state")) {
                        val mergedState = gson.fromJson(jsonResponse.get("state"), TimerState::class.java)
                        offlineSince = 0
                        Log.d(TAG, "Sync successful")
                        return@withContext mergedState
                    } else {
                        throw Exception("Response missing state field")
                    }
                } else {
                    throw Exception("Sync request failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            throw e
        }
    }

    private class SyncRequest {
        var state: TimerState? = null
        var offline_since: Long = 0
    }

    data class ConfigPayload(
        val durations: Durations,
        val long_break_after: Int,
        val daily_goal: Int,
        val day_start_hour: Int
    )

    data class Durations(
        val work: Int,
        val short_break: Int,
        val long_break: Int
    )

    suspend fun syncConfig(ip: String, port: Int, config: ConfigPayload) = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ip:$port/api/config"
            val json = gson.toJson(config)
            val body = json.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().close()
            Log.d(TAG, "Config sync successful")
        } catch (e: Exception) {
            Log.e(TAG, "Config sync failed", e)
            // We suppress config sync errors as they are not critical
        }
    }

    suspend fun fetchConfig(ip: String, port: Int): ConfigPayload? = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ip:$port/api/config"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val responseBody = response.body!!.string()
                    val config = gson.fromJson(responseBody, ConfigPayload::class.java)
                    Log.d(TAG, "Fetch config successful")
                    return@withContext config
                } else {
                    Log.e(TAG, "Fetch config failed: ${response.code}")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch config failed", e)
            return@withContext null
        }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val SYNC_TIMEOUT_SECONDS = 10
    }
}
