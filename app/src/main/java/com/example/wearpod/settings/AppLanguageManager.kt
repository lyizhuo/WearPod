package com.example.wearpod.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

object AppLanguageManager {
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_SIMPLIFIED_CHINESE = "zh-CN"

    private const val PREFS_NAME = "wearpod_settings"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun getSelectedLanguageTag(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null)
        return normalizeLanguageTag(stored ?: deviceDefaultLanguageTag())
    }

    fun updateLanguage(context: Context, languageTag: String) {
        val normalizedTag = normalizeLanguageTag(languageTag)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE_TAG, normalizedTag)
        }
        applyToResources(context.applicationContext, normalizedTag)
    }

    fun wrapContext(context: Context): Context {
        val normalizedTag = getSelectedLanguageTag(context)
        val locale = Locale.forLanguageTag(normalizedTag)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    @SuppressLint("ObsoleteSdkInt")
    fun applyToResources(context: Context, languageTag: String = getSelectedLanguageTag(context)) {
        val normalizedTag = normalizeLanguageTag(languageTag)
        val locale = Locale.forLanguageTag(normalizedTag)
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun deviceDefaultLanguageTag(): String {
        val defaultLocale = Locale.getDefault()
        return if (defaultLocale.language.equals(Locale.SIMPLIFIED_CHINESE.language, ignoreCase = true)) {
            LANGUAGE_SIMPLIFIED_CHINESE
        } else {
            LANGUAGE_ENGLISH
        }
    }

    private fun normalizeLanguageTag(languageTag: String): String {
        return when (languageTag) {
            LANGUAGE_SIMPLIFIED_CHINESE, "zh", "zh-Hans-CN", "zh-Hans" -> LANGUAGE_SIMPLIFIED_CHINESE
            else -> LANGUAGE_ENGLISH
        }
    }
}