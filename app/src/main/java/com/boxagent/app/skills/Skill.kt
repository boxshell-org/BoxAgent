package com.boxagent.app.skills

/**
 * A saved, reusable procedure. Instructions tell the agent how to do
 * something on this phone; optional [steps] replay it deterministically
 * (text selectors + `{{param}}` placeholders) — through the same
 * confirmation gate and audit log as any tool call.
 */
data class Skill(
    val id: Long = 0,
    /** Slug, unique: `web-search`. What the agent calls it by. */
    val name: String,
    val title: String,
    /** When to use it — one line, shown in the agent's skill index. */
    val description: String = "",
    val instructions: String = "",
    /** Packages it is about; skills for the app in front rank first. */
    val apps: List<String> = emptyList(),
    val params: List<SkillParam> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val enabled: Boolean = true,
    /** Proposed by the agent or imported: needs the user's review before
     *  any run can see it. */
    val draft: Boolean = false,
    val source: SkillSource = SkillSource.USER,
    val uses: Int = 0,
    val runs: Int = 0,
    val successes: Int = 0,
    val lastUsedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    val runnable: Boolean get() = steps.isNotEmpty()

    /** Offered to the agent. */
    val active: Boolean get() = enabled && !draft

    /** Success rate of step runs, when there are any. */
    val successRate: Int? get() = if (runs == 0) null else successes * 100 / runs
}

enum class SkillSource { BUILTIN, USER, RECORDED, AGENT, IMPORTED }

data class SkillParam(
    val name: String,
    val description: String = "",
    val required: Boolean = true,
    val default: String? = null,
)

data class SkillStep(
    val tool: String,
    /** JSON object text, may contain `{{param}}` / `{{param|url}}`. */
    val argsJson: String = "{}",
    /** Failure doesn't stop the run (e.g. a dialog that only sometimes shows). */
    val optional: Boolean = false,
)

/** Where a ref pointed when an action ran — used to make recorded steps
 *  replayable (refs only mean something within one run). */
data class RefTarget(
    /** The element's own text/description, or its row title. */
    val text: String?,
    val hint: String?,
    val cx: Int,
    val cy: Int,
    val editable: Boolean,
)
