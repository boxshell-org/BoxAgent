package com.boxagent.app.skills

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Skill (de)serialization: the core's config shape, Room columns, and the
 * shareable export format — plus validation and the helpers that turn a
 * recorded run into replayable, parameterized steps.
 */
object SkillCodec {
    const val FORMAT = 1
    private const val EXPORT_KEY = "boxagent_skill"
    private const val EXPORT_LIST_KEY = "boxagent_skills"

    private val SLUG = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val PARAM = Regex("^[A-Za-z_][A-Za-z0-9_]{0,30}$")
    private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(\\|\\s*([a-z]+)\\s*)?}}")

    /** `Order coffee!` → `order-coffee`; non-Latin titles fall back to `skill-<hash>`. */
    fun slugify(title: String): String {
        val s = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trimEnd('-')
        return s.ifEmpty { "skill-" + Integer.toHexString(title.hashCode()).takeLast(6) }
    }

    fun placeholders(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    /** Problems that would make the skill unusable; empty when fine. */
    fun validate(skill: Skill, knownTools: Set<String>? = null): List<String> {
        val out = mutableListOf<String>()
        if (!SLUG.matches(skill.name)) out += "name: lowercase letters, digits and dashes (max 40)"
        if (skill.title.isBlank()) out += "title is empty"
        if (skill.description.isBlank()) out += "description is empty"
        if (skill.instructions.isBlank() && skill.steps.isEmpty()) {
            out += "needs instructions or steps"
        }
        val names = skill.params.map { it.name }
        skill.params.forEach { p ->
            if (!PARAM.matches(p.name)) out += "parameter \"${p.name}\": letters, digits, _"
        }
        if (names.size != names.toSet().size) out += "duplicate parameter names"
        skill.steps.forEachIndexed { i, st ->
            val n = i + 1
            if (st.tool.isBlank()) out += "step $n: no tool"
            else if (knownTools != null && st.tool !in knownTools) out += "step $n: unknown tool ${st.tool}"
            val args = runCatching { JSONObject(st.argsJson.ifBlank { "{}" }) }.getOrNull()
            if (args == null) out += "step $n: args must be a JSON object"
            (placeholders(st.argsJson) - names.toSet()).forEach {
                out += "step $n uses {{$it}}, which is not a parameter"
            }
        }
        return out
    }

    // ---- core config ---------------------------------------------------

    fun toCore(s: Skill): JSONObject = JSONObject()
        .put("name", s.name)
        .put("title", s.title)
        .put("description", s.description)
        .put("instructions", s.instructions)
        .put("apps", JSONArray(s.apps))
        .put("params", paramsToJson(s.params))
        .put("steps", stepsToJson(s.steps))

    fun coreArray(skills: List<Skill>): JSONArray = JSONArray(skills.map(::toCore))

    // ---- storage columns -----------------------------------------------

    fun paramsToJson(params: List<SkillParam>): JSONArray = JSONArray(params.map { p ->
        JSONObject().put("name", p.name).put("description", p.description).apply {
            if (!p.required) put("required", false)
            p.default?.let { put("default", it) }
        }
    })

    fun paramsFromJson(text: String): List<SkillParam> =
        runCatching { JSONArray(text) }.getOrNull()?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isEmpty()) null else SkillParam(
                    name = name,
                    description = o.optString("description").trim(),
                    required = o.optBoolean("required", true),
                    default = if (o.has("default") && !o.isNull("default")) o.optString("default") else null,
                )
            }
        }.orEmpty()

    fun stepsToJson(steps: List<SkillStep>): JSONArray = JSONArray(steps.map { st ->
        JSONObject().put("tool", st.tool)
            .put("args", runCatching { JSONObject(st.argsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() })
            .apply { if (st.optional) put("optional", true) }
    })

    fun stepsFromJson(text: String): List<SkillStep> =
        runCatching { JSONArray(text) }.getOrNull()?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val tool = o.optString("tool").trim()
                if (tool.isEmpty()) null else SkillStep(
                    tool = tool,
                    argsJson = (o.optJSONObject("args") ?: JSONObject()).toString(),
                    optional = o.optBoolean("optional"),
                )
            }
        }.orEmpty()

    // ---- sharing --------------------------------------------------------

    private fun portableJson(s: Skill): JSONObject = toCore(s).put(EXPORT_KEY, FORMAT)

    /** Shareable text: one skill as an object, several as a list. */
    fun export(skills: List<Skill>): String =
        if (skills.size == 1) portableJson(skills[0]).toString(2)
        else JSONObject().put(EXPORT_LIST_KEY, FORMAT)
            .put("skills", JSONArray(skills.map(::portableJson))).toString(2)

    /**
     * Parse shared text (one skill, a list, or `{"skills": [...]}`).
     * Imported skills always arrive as drafts — they change what the agent
     * does later, so the user reviews them first.
     */
    fun import(text: String, now: Long = System.currentTimeMillis()): List<Skill> {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "nothing to import" }
        val objects: List<JSONObject> = when {
            trimmed.startsWith("[") -> JSONArray(trimmed).let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }
            else -> JSONObject(trimmed).let { o ->
                o.optJSONArray("skills")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                    ?: listOf(o)
            }
        }
        require(objects.isNotEmpty()) { "no skills found" }
        return objects.map { o ->
            val title = o.optString("title").trim().ifEmpty { o.optString("name").trim() }
            require(title.isNotEmpty()) { "a skill has no name" }
            Skill(
                name = o.optString("name").trim().lowercase().ifEmpty { slugify(title) }
                    .let { if (SLUG.matches(it)) it else slugify(it) },
                title = title,
                description = o.optString("description").trim(),
                instructions = o.optString("instructions").trim(),
                apps = o.optJSONArray("apps")?.let { a ->
                    (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
                }.orEmpty(),
                params = paramsFromJson(o.optJSONArray("params")?.toString() ?: "[]"),
                steps = stepsFromJson(o.optJSONArray("steps")?.toString() ?: "[]"),
                enabled = true,
                draft = true,
                source = SkillSource.IMPORTED,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    // ---- recording ------------------------------------------------------

    /**
     * Executed call → replayable step. Refs only mean something within one
     * run, so a ref becomes the element's text (or its coordinates when it
     * has none); transient flags like `observe` are dropped. Null when the
     * call can't be replayed.
     */
    fun portable(tool: String, args: JSONObject, target: RefTarget?): SkillStep? {
        val a = JSONObject(args.toString())
        a.remove("observe")
        if (a.has("ref")) {
            a.remove("ref")
            val t = target ?: return null
            when (tool) {
                "tap", "long_press" -> {
                    if (!t.text.isNullOrBlank()) a.put("text", t.text)
                    else a.put("x", t.cx).put("y", t.cy)
                }
                "type_text" -> {
                    // A field's text is its current value — only its hint
                    // identifies it; without one, type into the focused field.
                    if (!t.hint.isNullOrBlank()) a.put("target_text", t.hint)
                }
                "scroll" -> if (!t.text.isNullOrBlank()) a.put("text", t.text)
            }
        }
        return SkillStep(tool, a.toString())
    }

    /**
     * Replace each parameter's example value (what this run used) with its
     * placeholder in the recorded steps — URL-encoded occurrences become
     * `{{name|url}}`.
     */
    fun parameterize(steps: List<SkillStep>, examples: Map<String, String>): List<SkillStep> {
        val usable = examples.filterValues { it.length >= 2 }
            .entries.sortedByDescending { it.value.length }
        if (usable.isEmpty()) return steps
        fun sub(v: Any?): Any? = when (v) {
            is String -> usable.fold(v) { acc, (name, ex) ->
                val encoded = URLEncoder.encode(ex, "UTF-8").replace("+", "%20")
                val withRaw = acc.replace(ex, "{{$name}}")
                if (encoded != ex) withRaw.replace(encoded, "{{$name|url}}") else withRaw
            }
            is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, sub(v.get(k))) } }
            is JSONArray -> JSONArray().also { a -> (0 until v.length()).forEach { a.put(sub(v.get(it))) } }
            else -> v
        }
        return steps.map { st ->
            val args = runCatching { JSONObject(st.argsJson) }.getOrNull() ?: return@map st
            st.copy(argsJson = (sub(args) as JSONObject).toString())
        }
    }

    /**
     * Editor draft from a finished run ("Save as skill"): the prompt names
     * it, the recorded steps make it runnable, and the instructions say
     * what worked so the agent can adapt when a step no longer matches.
     */
    fun fromRun(prompt: String, summary: String, steps: List<SkillStep>): Skill {
        val title = prompt.lineSequence().first().trim().take(48).ifEmpty { "Skill" }
        val instructions = buildString {
            append("Goal: ").append(prompt.trim())
            if (summary.isNotBlank() && summary.trim() != prompt.trim()) {
                append("\nOutcome last time: ").append(summary.trim().take(300))
            }
            if (steps.isNotEmpty()) {
                append("\nSteps that worked:")
                steps.forEachIndexed { i, st -> append("\n").append(i + 1).append(". ").append(describe(st)) }
            }
        }
        return Skill(
            name = slugify(title),
            title = title,
            description = prompt.trim().take(120),
            instructions = instructions,
            steps = steps,
            source = SkillSource.RECORDED,
        )
    }

    /** A step as one readable line for the UI: `tap · "Wi-Fi"`. */
    fun describe(step: SkillStep): String {
        val a = runCatching { JSONObject(step.argsJson) }.getOrNull() ?: return step.tool
        val main = listOf("text", "name", "uri", "package", "direction", "target_text", "cmd")
            .firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }
        val detail = when {
            main != null && main.first in setOf("text", "target_text") -> "\"${main.second}\""
            main != null -> main.second
            a.has("x") && a.has("y") -> "${a.optInt("x")}, ${a.optInt("y")}"
            else -> ""
        }
        val typed = if (step.tool == "type_text" && a.optBoolean("submit")) " ⏎" else ""
        return listOf(step.tool, detail + typed).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
