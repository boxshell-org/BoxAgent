package com.boxagent.app.skills

/**
 * Skills that ship with the app. Runnable ones use standard Android
 * intents — they behave the same on every OEM skin and UI language; the
 * rest are instructions, since their on-screen labels vary by device.
 * Titles/descriptions follow the UI language; instructions are written for
 * the model.
 */
object BuiltinSkills {
    private fun s(
        name: String,
        title: Pair<String, String>,
        description: Pair<String, String>,
        instructions: String,
        params: List<SkillParam> = emptyList(),
        steps: List<SkillStep> = emptyList(),
        apps: List<String> = emptyList(),
    ) = Triple(name, title to description, Skill(
        name = name, title = title.first, description = description.first,
        instructions = instructions.trimIndent(), params = params, steps = steps, apps = apps,
        source = SkillSource.BUILTIN,
    ))

    private val all = listOf(
        s(
            "web-search",
            "Web search" to "网页搜索",
            "Search the web for a query in the browser" to "在浏览器中搜索关键词",
            """
            Opens a Bing results page for the query in the default browser (works in
            every region). Read the results with `screen`; tap a result to open it.
            """,
            params = listOf(SkillParam("query", "What to search for")),
            steps = listOf(SkillStep("launch_intent", """{"uri":"https://www.bing.com/search?q={{query|url}}"}""")),
        ),
        s(
            "open-url",
            "Open a link" to "打开链接",
            "Open a web address or app link" to "打开网址或应用链接",
            "Opens the URL with the app that handles it (browser or the app itself).",
            params = listOf(SkillParam("url", "Full URL, e.g. https://example.com")),
            steps = listOf(SkillStep("launch_intent", """{"uri":"{{url}}"}""")),
        ),
        s(
            "navigate-to",
            "Directions" to "导航到",
            "Show a place in the maps app" to "在地图应用中查找地点",
            """
            Opens the default maps app searching for the destination (geo: URI).
            To start navigation, tap the app's Directions / Start button afterwards.
            """,
            params = listOf(SkillParam("destination", "Place name or address")),
            steps = listOf(SkillStep("launch_intent", """{"uri":"geo:0,0?q={{destination|url}}"}""")),
        ),
        s(
            "set-alarm",
            "Set an alarm" to "设置闹钟",
            "Create an alarm in the clock app" to "在时钟应用中新建闹钟",
            """
            Uses the standard SET_ALARM intent; the clock app shows it for the user
            to confirm. hour is 0-23, minute 0-59.
            """,
            params = listOf(
                SkillParam("hour", "Hour, 0-23"),
                SkillParam("minute", "Minute, 0-59", required = false, default = "0"),
                SkillParam("label", "Alarm label", required = false, default = ""),
            ),
            steps = listOf(SkillStep(
                "launch_intent",
                """{"uri":"intent:#Intent;action=android.intent.action.SET_ALARM;i.android.intent.extra.alarm.HOUR={{hour}};i.android.intent.extra.alarm.MINUTES={{minute}};S.android.intent.extra.alarm.MESSAGE={{label|url}};end"}""",
            )),
        ),
        s(
            "set-timer",
            "Start a timer" to "开始计时",
            "Start a countdown timer" to "启动倒计时",
            "Uses the standard SET_TIMER intent. seconds is the countdown length.",
            params = listOf(SkillParam("seconds", "Length in seconds")),
            steps = listOf(SkillStep(
                "launch_intent",
                """{"uri":"intent:#Intent;action=android.intent.action.SET_TIMER;i.android.intent.extra.alarm.LENGTH={{seconds}};B.android.intent.extra.alarm.SKIP_UI=false;end"}""",
            )),
        ),
        s(
            "open-settings-page",
            "Open a settings page" to "打开设置页面",
            "Jump straight to a system settings page" to "直接打开某个系统设置页面",
            """
            page is the action suffix of android.settings.*, e.g. WIFI_SETTINGS,
            BLUETOOTH_SETTINGS, DISPLAY_SETTINGS, SOUND_SETTINGS,
            LOCATION_SOURCE_SETTINGS, APPLICATION_SETTINGS, BATTERY_SAVER_SETTINGS,
            DATE_SETTINGS, INTERNAL_STORAGE_SETTINGS, ACCESSIBILITY_SETTINGS.
            If the page doesn't exist on this device, use find-setting instead.
            """,
            params = listOf(SkillParam("page", "e.g. WIFI_SETTINGS")),
            steps = listOf(SkillStep("launch_intent", """{"uri":"android.settings.{{page}}"}""")),
            apps = listOf("com.android.settings"),
        ),
        s(
            "app-details",
            "App info" to "应用信息",
            "Open an app's info page (storage, permissions, force stop)" to "打开应用信息页（存储、权限、强行停止）",
            "Opens Settings › Apps › <app>. Find the package with app_list query=… first if unsure.",
            params = listOf(SkillParam("package", "Package name, e.g. com.android.chrome")),
            steps = listOf(SkillStep(
                "launch_intent",
                """{"uri":"intent:package:{{package}}#Intent;action=android.settings.APPLICATION_DETAILS_SETTINGS;end"}""",
            )),
            apps = listOf("com.android.settings"),
        ),
        s(
            "find-setting",
            "Find a setting" to "查找设置项",
            "Find any setting by searching in Settings" to "在设置中搜索并打开某项设置",
            """
            1. launch_intent uri=android.settings.SETTINGS.
            2. Tap the search field or magnifier at the top (labels vary by device and
               language, e.g. "Search settings", "搜索设置").
            3. type_text the term with submit=true.
            4. Open the result whose title matches best; results often show the
               path (e.g. Network › Wi-Fi) under the title.

            If search finds nothing, try a synonym or the English term.
            """,
            params = listOf(SkillParam("term", "Setting to find, e.g. dark theme")),
            apps = listOf("com.android.settings"),
        ),
        s(
            "clear-notifications",
            "Clear notifications" to "清除通知",
            "Dismiss all notifications" to "清除全部通知",
            """
            1. key name=notifications to open the shade.
            2. Scroll down to the end of the list; tap the clear button ("Clear all",
               "Dismiss all", "全部清除" or a trash/✕ icon — use screenshot if unclear).
            3. Ongoing notifications (music, navigation, this app) can't be cleared —
               that's expected. key name=back to close the shade.
            """,
        ),
        s(
            "dark-mode",
            "Dark theme" to "深色模式",
            "Turn the system dark theme on or off" to "开启或关闭系统深色主题",
            """
            Preferred: launch_intent uri=android.settings.DISPLAY_SETTINGS, then toggle
            "Dark theme" (labels vary: "深色模式", "Dark mode"); check its state in the
            returned screen. Shell alternative (needs confirmation):
            shell_exec cmd="cmd uimode night yes" (or "no").
            """,
            apps = listOf("com.android.settings"),
        ),
    )

    /** The library in the UI language (`zh` or default English). */
    fun forLocale(language: String, now: Long = System.currentTimeMillis()): List<Skill> {
        val zh = language.startsWith("zh")
        return all.map { (_, text, skill) ->
            skill.copy(
                title = if (zh) text.first.second else text.first.first,
                description = if (zh) text.second.second else text.second.first,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    val names: Set<String> get() = all.map { it.first }.toSet()
}
