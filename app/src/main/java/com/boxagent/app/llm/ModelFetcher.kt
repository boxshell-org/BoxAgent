package com.boxagent.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fetches the model list from an OpenAI-compatible `GET {baseUrl}/models`. */
object ModelFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun list(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trim().trimEnd('/') + "/models"
            val key = apiKey.trim()
            val req = Request.Builder().url(url).apply {
                if (key.isNotEmpty()) header("Authorization", "Bearer $key")
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw IOException("empty body")
                val arr = JSONObject(body).optJSONArray("data")
                    ?: throw IOException("no models in response")
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("id")
                            ?.takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }.sorted()
            }
        }
}
