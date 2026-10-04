package com.boxagent.app.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A model entry from the models.dev catalog. */
data class CatalogModel(
    val id: String,
    val name: String,
    val context: Int,
    val toolCall: Boolean,
    val reasoning: Boolean,
)

/** A provider entry from the models.dev catalog. */
data class CatalogProvider(
    val id: String,
    val name: String,
    val api: String,
    val models: List<CatalogModel>,
)

/**
 * Fetches and caches the models.dev provider catalog (https://models.dev/api.json).
 * Falls back to the last cached copy on disk when the network is unavailable.
 */
object ModelsDev {

    private const val URL = "https://models.dev/api.json"
    private const val CACHE_FILE = "models_dev.json"

    // models.dev only ships `api` for providers that publish a fixed
    // OpenAI-compatible base URL. Well-known providers without one get a
    // static fallback here; the rest are filtered out.
    private val KNOWN_ENDPOINTS = mapOf(
        "openai" to "https://api.openai.com/v1",
        "anthropic" to "https://api.anthropic.com/v1",
        "xai" to "https://api.x.ai/v1",
        "groq" to "https://api.groq.com/openai/v1",
        "mistral" to "https://api.mistral.ai/v1",
        "togetherai" to "https://api.together.xyz/v1",
        "deepinfra" to "https://api.deepinfra.com/v1/openai",
        "cerebras" to "https://api.cerebras.ai/v1",
        "perplexity" to "https://api.perplexity.ai",
        "google" to "https://generativelanguage.googleapis.com/v1beta/openai/",
        "cohere" to "https://api.cohere.com/compatibility/v1",
        "venice" to "https://api.venice.ai/api/v1",
        "vercel" to "https://ai-gateway.vercel.sh/v1",
        "aihubmix" to "https://aihubmix.com/v1",
        "v0" to "https://api.v0.dev/v1",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cached: List<CatalogProvider>? = null

    suspend fun catalog(ctx: Context): List<CatalogProvider> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        var disk = false
        val raw = try {
            httpGet(URL).also { body ->
                runCatching { File(ctx.filesDir, CACHE_FILE).writeText(body) }
            }
        } catch (e: Exception) {
            readDiskCache(ctx)?.also { disk = true } ?: throw e
        }
        // A corrupt disk cache must not poison the catalog forever —
        // surface the original fetch error instead of a parse error.
        runCatching { parse(raw) }.getOrElse { pe ->
            if (disk) throw IOException("catalog unavailable (cache corrupt)")
            throw pe
        }.also { cached = it }
    }

    /** Match a provider by its OpenAI-compatible base URL (normalized). */
    suspend fun providerForUrl(ctx: Context, url: String): CatalogProvider? {
        val n = norm(url)
        return catalog(ctx).firstOrNull { norm(it.api) == n }
    }

    /** Non-suspending lookup on the in-memory cache only (null if not loaded). */
    fun peekProviderForUrl(url: String): CatalogProvider? {
        val n = norm(url)
        return cached?.firstOrNull { norm(it.api) == n }
    }

    /** Lowercase, drop scheme + trailing slash + common API path suffixes. */
    private fun norm(u: String): String {
        var s = u.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        for (suf in listOf("/compatibility/v1", "/v1beta/openai", "/openai/v1",
            "/api/v1", "/v1")) {
            if (s.endsWith(suf)) {
                s = s.removeSuffix(suf)
                break
            }
        }
        return s
    }

    private fun httpGet(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("empty body")
        }

    private fun readDiskCache(ctx: Context): String? {
        val f = File(ctx.filesDir, CACHE_FILE)
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    internal fun parse(raw: String): List<CatalogProvider> {
        val root = JSONObject(raw)
        val out = mutableListOf<CatalogProvider>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val pid = keys.next()
            val p = root.optJSONObject(pid) ?: continue
            val api = p.optString("api").ifBlank { KNOWN_ENDPOINTS[pid] ?: "" }
            if (api.isBlank()) continue
            val modelsObj = p.optJSONObject("models") ?: continue
            val models = mutableListOf<CatalogModel>()
            val mKeys = modelsObj.keys()
            while (mKeys.hasNext()) {
                val mid = mKeys.next()
                val m = modelsObj.optJSONObject(mid) ?: continue
                models += CatalogModel(
                    id = m.optString("id").ifBlank { mid },
                    name = m.optString("name").ifBlank { mid },
                    context = m.optJSONObject("limit")?.optInt("context") ?: 0,
                    toolCall = m.optBoolean("tool_call"),
                    reasoning = m.optBoolean("reasoning"),
                )
            }
            out += CatalogProvider(
                id = p.optString("id").ifBlank { pid },
                name = p.optString("name").ifBlank { pid },
                api = api,
                models = models.sortedBy { it.id },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }
}
