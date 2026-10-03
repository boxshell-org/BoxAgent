package com.boxagent.app.agent

import com.boxagent.app.bridge.Core
import org.json.JSONArray
import org.json.JSONObject

data class ToolSpec(
    val name: String,
    val summary: String,
    val description: String,
    val risk: String,
    val backend: String,
    val parameters: JSONObject,
)

/** Parsed copy of the Rust tool registry (nativeToolCatalog). */
object ToolCatalog {
    private var cache: List<ToolSpec>? = null

    fun all(): List<ToolSpec> = cache ?: load().also { cache = it }

    fun byName(name: String): ToolSpec? = all().firstOrNull { it.name == name }

    private fun load(): List<ToolSpec> {
        val arr = JSONArray(Core.nativeToolCatalog())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ToolSpec(
                name = o.getString("name"),
                summary = o.optString("summary"),
                description = o.optString("description"),
                risk = o.optString("risk"),
                backend = o.optString("backend"),
                parameters = o.optJSONObject("parameters") ?: JSONObject(),
            )
        }
    }
}
