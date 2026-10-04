package com.boxagent.app.skills

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCodecTest {
    private val tools = setOf(
        "tap", "long_press", "type_text", "scroll", "key", "launch_intent", "app_launch",
        "shell_exec", "wait_for",
    )

    @Test
    fun builtinsAreValidInBothLanguages() {
        for (lang in listOf("en", "zh-CN")) {
            val skills = BuiltinSkills.forLocale(lang)
            assertEquals(BuiltinSkills.names, skills.map { it.name }.toSet())
            skills.forEach { s ->
                assertEquals("${s.name}: ${SkillCodec.validate(s, tools)}", emptyList<String>(),
                    SkillCodec.validate(s, tools))
                assertEquals(SkillSource.BUILTIN, s.source)
            }
        }
        assertEquals("网页搜索", BuiltinSkills.forLocale("zh").first { it.name == "web-search" }.title)
    }

    @Test
    fun slugs() {
        assertEquals("order-coffee", SkillCodec.slugify("Order coffee!"))
        assertEquals("wi-fi-on", SkillCodec.slugify("  Wi-Fi  ON "))
        assertTrue(SkillCodec.slugify("打开微信").startsWith("skill-"))
    }

    @Test
    fun validationCatchesBrokenSkills() {
        val bad = Skill(
            name = "Bad Name", title = "", description = "",
            params = listOf(SkillParam("ok"), SkillParam("ok"), SkillParam("9x")),
            steps = listOf(
                SkillStep("tap", """{"text":"{{missing}}"}"""),
                SkillStep("teleport", "{}"),
                SkillStep("tap", "not json"),
            ),
        )
        val problems = SkillCodec.validate(bad, tools)
        listOf("name:", "title is empty", "description is empty", "duplicate parameter",
            "parameter \"9x\"", "{{missing}}", "unknown tool teleport", "step 3: args")
            .forEach { needle -> assertTrue("$needle in $problems", problems.any { needle in it }) }
    }

    @Test
    fun exportImportRoundTripArrivesAsDraft() {
        val s = BuiltinSkills.forLocale("en").first { it.name == "set-alarm" }
        val text = SkillCodec.export(listOf(s))
        assertTrue(text.contains("\"boxagent_skill\": 1"))
        val back = SkillCodec.import(text).single()
        assertEquals(s.name, back.name)
        assertEquals(s.params, back.params)
        assertEquals(s.steps.map { JSONObject(it.argsJson).toString() },
            back.steps.map { JSONObject(it.argsJson).toString() })
        assertTrue("imports need review", back.draft)
        assertEquals(SkillSource.IMPORTED, back.source)

        val many = SkillCodec.import(SkillCodec.export(BuiltinSkills.forLocale("en")))
        assertEquals(BuiltinSkills.names.size, many.size)
        // Bare arrays and odd names are tolerated.
        val loose = SkillCodec.import("""[{"title":"Say Hi","instructions":"wave"}]""").single()
        assertEquals("say-hi", loose.name)
    }

    @Test
    fun recordedRefsBecomeTextSelectors() {
        val t = RefTarget(text = "Wi-Fi", hint = null, cx = 10, cy = 20, editable = false)
        val tap = SkillCodec.portable("tap", JSONObject("""{"ref":4,"observe":false}"""), t)!!
        assertEquals("""{"text":"Wi-Fi"}""", tap.argsJson)
        val icon = SkillCodec.portable("tap", JSONObject("""{"ref":5}"""),
            t.copy(text = null))!!
        val xy = JSONObject(icon.argsJson)
        assertEquals(10, xy.getInt("x"))
        assertEquals(20, xy.getInt("y"))
        assertFalse(xy.has("text"))
        val field = SkillCodec.portable("type_text", JSONObject("""{"ref":6,"text":"cats","submit":true}"""),
            RefTarget(text = "old value", hint = "Search", cx = 0, cy = 0, editable = true))!!
        val f = JSONObject(field.argsJson)
        assertEquals("Search", f.getString("target_text"))
        assertEquals("cats", f.getString("text"))
        assertFalse(f.has("ref"))
        // A ref we can't resolve can't be replayed.
        assertNull(SkillCodec.portable("tap", JSONObject("""{"ref":9}"""), null))
    }

    @Test
    fun examplesBecomePlaceholders() {
        val steps = listOf(
            SkillStep("launch_intent", """{"uri":"https://www.bing.com/search?q=cats%20%26%20dogs"}"""),
            SkillStep("type_text", """{"text":"cats & dogs","submit":true}"""),
            SkillStep("tap", """{"text":"Search"}"""),
        )
        val p = SkillCodec.parameterize(steps, mapOf("query" to "cats & dogs"))
        assertEquals("https://www.bing.com/search?q={{query|url}}", JSONObject(p[0].argsJson).getString("uri"))
        assertEquals("{{query}}", JSONObject(p[1].argsJson).getString("text"))
        assertEquals(steps[2], p[2])
        val skill = Skill(name = "s", title = "S", description = "d",
            params = listOf(SkillParam("query")), steps = p)
        assertEquals(emptyList<String>(), SkillCodec.validate(skill, tools))
    }

    @Test
    fun describesStepsForHumans() {
        assertEquals("tap · \"Wi-Fi\"", SkillCodec.describe(SkillStep("tap", """{"text":"Wi-Fi"}""")))
        assertEquals("type_text · \"{{q}}\" ⏎",
            SkillCodec.describe(SkillStep("type_text", """{"text":"{{q}}","submit":true}""")))
        assertEquals("key · back", SkillCodec.describe(SkillStep("key", """{"name":"back"}""")))
    }
}
