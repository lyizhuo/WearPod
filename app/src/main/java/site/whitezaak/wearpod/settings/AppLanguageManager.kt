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

    /** 迁移标记：置位后，API 33+ 的「applicationLocales 为空」就表示用户在系统里选了「跟随系统」。 */
    private const val KEY_LEGACY_MIGRATED = "language_legacy_migrated"

    /**
     * 日期/元信息是每个列表项都要算的，而 API 33+ 的 [LocaleManager.applicationLocales]
     * 是一次 binder IPC。缓存由 [wrapContext]（每次 Activity 重建时刷新）与
     * [updateLanguage] 维护，系统侧改语言会重建 Activity，因此不会读到过期值。
     */
    @Volatile
    private var cachedLocale: Locale? = null

    fun getSelectedLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) {
                return normalizeLanguageTag(locales.toLanguageTags())
            }
            // 还没迁移过的老用户仍要读旧 prefs（升级首次启动）；
            // 迁移之后空列表就是「跟随系统」，不能让残留 prefs 覆盖系统选择。
            if (!isLegacyMigrated(context)) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LANGUAGE_TAG, null)
                    ?.let { return normalizeLanguageTag(it) }
            }
            return deviceDefaultLanguageTag()
        }
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null)
        return normalizeLanguageTag(stored ?: deviceDefaultLanguageTag())
    }

    fun getSelectedLocale(context: Context): Locale =
        cachedLocale ?: Locale.forLanguageTag(getSelectedLanguageTag(context)).also { cachedLocale = it }

    fun updateLanguage(context: Context, languageTag: String) {
        val normalizedTag = normalizeLanguageTag(languageTag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 33+ 只写 LocaleManager：再写 prefs 会让「系统设置里改回跟随系统」失效。
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_LEGACY_MIGRATED, true)
                remove(KEY_LANGUAGE_TAG)
            }
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(normalizedTag)
            cachedLocale = Locale.forLanguageTag(normalizedTag)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_LANGUAGE_TAG, normalizedTag)
            }
            // API 30–32 没有 LocaleManager，只能更新应用资源；Activity 由调用方 recreate。
            applyToResources(context.applicationContext, normalizedTag)
            cachedLocale = Locale.forLanguageTag(normalizedTag)
        }
    }

    /**
     * 老版本升级到 Android 13+ 时，把旧 prefs 里的选择迁移到 LocaleManager，
     * 避免系统设置页显示的语言与应用内语言不一致。迁移后清掉旧键并置标记。
     */
    fun migrateLegacyPreference(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isLegacyMigrated(context)) return
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null)
        if (localeManager.applicationLocales.isEmpty && stored != null) {
            localeManager.applicationLocales =
                LocaleList.forLanguageTags(normalizeLanguageTag(stored))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_LEGACY_MIGRATED, true)
            remove(KEY_LANGUAGE_TAG)
        }
        cachedLocale = null
    }

    private fun isLegacyMigrated(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEGACY_MIGRATED, false)

    fun wrapContext(context: Context): Context {
        val normalizedTag = getSelectedLanguageTag(context)
        val locale = Locale.forLanguageTag(normalizedTag)
        // 每次 Activity 重建都会走到这里（包括系统侧改语言导致的配置变更），
        // 因此这是刷新 [cachedLocale] 的正确时机。
        cachedLocale = locale

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
