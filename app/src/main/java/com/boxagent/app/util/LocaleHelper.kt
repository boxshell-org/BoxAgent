package com.boxagent.app.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * In-app language selection: "system" | "en" | "zh".
 *
 * API 33+ routes through [LocaleManager.applicationLocales] (same store the
 * system per-app language picker uses — also exposed via android:localeConfig).
 * API 30–32 wraps the Activity base context via [wrap]; the picker then calls
 * Activity.recreate(). A dedicated SharedPreferences keeps the boot path
 * synchronous (attachBaseContext can't suspend for DataStore).
 */
object LocaleHelper {
    private const val PREFS = "boxagent_locale"
    private const val KEY = "language"

    val SUPPORTED = listOf("system", "en", "zh")

    private val _language = MutableStateFlow("system")
    val language: StateFlow<String> = _language

    fun init(context: Context) {
        _language.value = saved(context)
    }

    fun saved(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "system") ?: "system"

    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, tag).apply()
        _language.value = tag
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == "system") LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
        }
    }

    /** Wrap the Activity base context with the saved locale on API < 33. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        return when (saved(base)) {
            "en" -> withLocale(base, Locale.ENGLISH)
            "zh" -> withLocale(base, Locale.SIMPLIFIED_CHINESE)
            else -> base
        }
    }

    private fun withLocale(base: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }
}
