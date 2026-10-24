package site.whitezaak.wearpod.settings

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

object AppLanguageManager {
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_SIMPLIFIED_CHINESE = "zh-CN"

    private const val PREFS_NAME = "wearpod_settings"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun getSelectedLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) {
                return normalizeLanguageTag(locales.toLanguageTags())
            }
        }
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null)
        return normalizeLanguageTag(stored ?: deviceDefaultLanguageTag())
    }

    fun getSelectedLocale(context: Context): Locale =
        Locale.forLanguageTag(getSelectedLanguageTag(context))

    fun updateLanguage(context: Context, languageTag: String) {
        val normalizedTag = normalizeLanguageTag(languageTag)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE_TAG, normalizedTag)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 的官方 per-app language API；系统会负责持久化与配置更新。
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(normalizedTag)
        } else {
            // API 30–32 没有 LocaleManager，只能更新应用资源；Activity 由调用方 recreate。
            applyToResources(context.applicationContext, normalizedTag)
        }
    }

    /**
     * 老版本升级到 Android 13+ 时，把旧 prefs 里的选择迁移到 LocaleManager，
     * 避免系统设置页显示的语言与应用内语言不一致。
     */
    fun migrateLegacyPreference(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val localeManager = context.getSystemService(LocaleManager::class.java)
        if (!localeManager.applicationLocales.isEmpty) return
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null) ?: return
        localeManager.applicationLocales =
            LocaleList.forLanguageTags(normalizeLanguageTag(stored))
    }

    fun wrapContext(context: Context): Context {
        val normalizedTag = getSelectedLanguageTag(context)
        val locale = Locale.forLanguageTag(normalizedTag)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    /**
     * 仅用于 API 30–32 的应用资源回退路径。Android 13+ 使用 [LocaleManager]，
     * 不再调用已废弃的 `Resources.updateConfiguration`，也不修改进程级
     * `Locale.setDefault`（那会影响所有线程和格式化行为）。
     */
    @SuppressLint("ObsoleteSdkInt")
    @Suppress("DEPRECATION")
    fun applyToResources(
        context: Context,
        languageTag: String = getSelectedLanguageTag(context),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val normalizedTag = normalizeLanguageTag(languageTag)
        val locale = Locale.forLanguageTag(normalizedTag)
        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
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
