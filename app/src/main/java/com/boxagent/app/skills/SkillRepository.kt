package com.boxagent.app.skills

import com.boxagent.app.data.Settings
import com.boxagent.app.data.db.AppDb
import com.boxagent.app.data.db.SkillEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** The skill library: Room-backed, plus seeding, sharing and agent drafts. */
class SkillRepository(
    private val db: AppDb,
    private val settings: Settings,
    /** Tool names for validation (the core's catalog); null skips the check. */
    private val knownTools: () -> Set<String>?,
) {
    private val dao get() = db.skills()

    /** Display order: drafts to review first, then most used. */
    val all: Flow<List<Skill>> = dao.all().map { list ->
        list.map(::fromEntity).sortedWith(
            compareByDescending<Skill> { it.draft }
                .thenByDescending { it.enabled }
                .thenByDescending { it.uses }
                .thenBy { it.title.lowercase() },
        )
    }

    /** Add built-ins not seeded before. One the user deleted stays deleted. */
    suspend fun seedBuiltins(language: String) {
        val seeded = settings.seededSkills.first()
        val existing = dao.list().map { it.name }.toSet()
        val fresh = BuiltinSkills.forLocale(language)
            .filter { it.name !in seeded && it.name !in existing }
        fresh.forEach { dao.insert(toEntity(it)) }
        if (fresh.isNotEmpty() || !seeded.containsAll(BuiltinSkills.names)) {
            settings.setSeededSkills(seeded + BuiltinSkills.names)
        }
    }

    /** Skills offered to a run: active ones, those for [foreground] first. */
    suspend fun forRun(foreground: String?): List<Skill> =
        dao.list().map(::fromEntity).filter { it.active }.sortedWith(
            compareByDescending<Skill> { foreground != null && foreground in it.apps }
                .thenByDescending { it.uses }
                .thenByDescending { it.lastUsedAt }
                .thenBy { it.name },
        )

    suspend fun byName(name: String): Skill? = dao.byName(name)?.let(::fromEntity)

    /** Insert or update; fails with a readable message when invalid. */
    suspend fun save(skill: Skill): Result<Skill> = runCatching {
        val problems = SkillCodec.validate(skill, knownTools())
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val clash = dao.byName(skill.name)
        require(clash == null || clash.id == skill.id) { "a skill named \"${skill.name}\" already exists" }
        val now = System.currentTimeMillis()
        if (skill.id == 0L) {
            val e = toEntity(skill.copy(createdAt = now, updatedAt = now))
            skill.copy(id = dao.insert(e))
        } else {
            skill.copy(updatedAt = now).also { dao.update(toEntity(it)) }
        }
    }

    // Simple mutators are called fire-and-forget from UI scopes — a Room
    // failure should degrade silently rather than kill the caller's job.
    suspend fun delete(id: Long) = runCatching { dao.delete(id) }

    suspend fun setEnabled(s: Skill, on: Boolean) {
        runCatching {
            dao.byId(s.id)?.let {
                dao.update(it.copy(enabled = on, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    /** Draft reviewed: the agent may use it from the next run. */
    suspend fun approve(s: Skill) {
        runCatching {
            dao.byId(s.id)?.let {
                dao.update(it.copy(draft = false, enabled = true, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun recordUse(name: String, run: Boolean, ok: Boolean) =
        runCatching { dao.recordUse(name, if (run) 1 else 0, if (run && ok) 1 else 0) }

    /** First free slug for [base]: `base`, `base-2`, … */
    suspend fun uniqueName(base: String): String {
        val taken = dao.list().map { it.name }.toSet()
        if (base !in taken) return base
        return (2..999).map { "$base-$it".takeLast(40).trimStart('-') }.first { it !in taken }
    }

    /** Import shared text; every skill arrives as a draft. Returns the count. */
    suspend fun importText(text: String): Result<Int> = runCatching {
        val incoming = SkillCodec.import(text)
        incoming.forEach { s ->
            val named = s.copy(name = uniqueName(s.name))
            save(named).getOrThrow()
        }
        incoming.size
    }

    fun exportText(skills: List<Skill>): String = SkillCodec.export(skills)

    /**
     * `save_skill` from the agent: stored as a draft for the user to
     * review. Recorded steps are parameterized from the example values
     * the model reports. Returns the tool result for the model.
     */
    suspend fun proposeFromAgent(a: JSONObject, trace: List<SkillStep>): JSONObject {
        val given = a.optString("name").trim()
        val name = uniqueName(SkillCodec.slugify(given))
        val params = mutableListOf<SkillParam>()
        val examples = mutableMapOf<String, String>()
        a.optJSONArray("params")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pn = p.optString("name").trim()
                if (pn.isEmpty()) continue
                params += SkillParam(pn, p.optString("description").trim())
                p.optString("example").takeIf { it.isNotBlank() }?.let { examples[pn] = it }
            }
        }
        val steps = if (a.optBoolean("record_steps", true)) {
            SkillCodec.parameterize(trace, examples)
        } else emptyList()
        val skill = Skill(
            name = name,
            title = humanize(given),
            description = a.optString("description").trim(),
            instructions = a.optString("instructions").trim(),
            params = params,
            steps = steps,
            enabled = true,
            draft = true,
            source = SkillSource.AGENT,
        )
        return save(skill).fold(
            onSuccess = {
                JSONObject().put("ok", true).put("saved", it.name).put("draft", true)
                    .put("steps", it.steps.size)
                    .put("note", "Saved as a draft; the user reviews it in Skills before it is used.")
            },
            onFailure = { e -> JSONObject().put("ok", false).put("error", e.message ?: "invalid skill") },
        )
    }

    private fun humanize(s: String): String {
        val t = s.replace('-', ' ').replace('_', ' ').trim()
        return t.replaceFirstChar { it.uppercase() }.ifEmpty { "Skill" }
    }

    companion object {
        fun fromEntity(e: SkillEntity) = Skill(
            id = e.id,
            name = e.name,
            title = e.title,
            description = e.description,
            instructions = e.instructions,
            apps = e.apps.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            params = SkillCodec.paramsFromJson(e.paramsJson),
            steps = SkillCodec.stepsFromJson(e.stepsJson),
            enabled = e.enabled,
            draft = e.draft,
            source = runCatching { SkillSource.valueOf(e.source) }.getOrDefault(SkillSource.USER),
            uses = e.uses,
            runs = e.runs,
            successes = e.successes,
            lastUsedAt = e.lastUsedAt,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )

        fun toEntity(s: Skill) = SkillEntity(
            id = s.id,
            name = s.name,
            title = s.title,
            description = s.description,
            instructions = s.instructions,
            apps = s.apps.joinToString("\n"),
            paramsJson = SkillCodec.paramsToJson(s.params).toString(),
            stepsJson = SkillCodec.stepsToJson(s.steps).toString(),
            enabled = s.enabled,
            draft = s.draft,
            source = s.source.name,
            uses = s.uses,
            runs = s.runs,
            successes = s.successes,
            lastUsedAt = s.lastUsedAt,
            createdAt = s.createdAt,
            updatedAt = s.updatedAt,
        )
    }
}
