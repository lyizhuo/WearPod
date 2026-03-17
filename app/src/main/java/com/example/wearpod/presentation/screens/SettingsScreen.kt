package com.example.wearpod.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import android.util.TypedValue
import android.widget.TextView
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
// 仅增加这两个必要的导入
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.*
import androidx.core.content.edit
import com.example.wearpod.R
import com.example.wearpod.settings.AppLanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun SettingsScreen(
    currentOpmlId: String?,
    onLoadOpml: (String) -> Unit,
    onLanguageClick: () -> Unit
) {
    val context = LocalContext.current
    var inputId by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    var aboutContent by remember { mutableStateOf<String?>(null) }
    var isLoadingAbout by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Warm up about content cache so Settings->About opens faster.
        val cached = loadAboutFromCache(context)
        if (cached != null) {
            aboutContent = cached
        }
    }

    // 定义状态
    val listState = rememberScalingLazyListState()

    if (showAbout) {
        BackHandler { showAbout = false }
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
            state = listState,
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
                scrollableState = listState,
                hapticFeedbackEnabled = false
            )
        ) {
            item {
                if (isLoadingAbout) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
                } else if (aboutContent != null) {
                    val parsedAbout = remember(aboutContent) {
                        normalizeHtmlBodySize(
                            HtmlCompat.fromHtml(
                                aboutContent!!,
                                HtmlCompat.FROM_HTML_MODE_COMPACT
                            )
                        )
                    }
                    AndroidView(
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        factory = { context ->
                            TextView(context).apply {
                                setTextColor(android.graphics.Color.WHITE)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                setLineSpacing(0f, 1.1f)
                            }
                        },
                        update = { textView ->
                            textView.text = parsedAbout
                        }
                    )
                } else {
                    Text(stringResource(R.string.settings_about_load_failed), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        // 绑定状态并禁用导致闪退的震动反馈
        state = listState,
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
            scrollableState = listState,
            hapticFeedbackEnabled = false
        )
    ) {
        item {
            ListHeader {
                Text(text = stringResource(R.string.nav_settings), textAlign = TextAlign.Center)
            }
        }
        item {
            Text(
                text = stringResource(R.string.settings_opml_section),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            )
        }
        item {
            Text(
                text = stringResource(
                    R.string.settings_current_id,
                    currentOpmlId ?: stringResource(R.string.settings_local_default)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f)
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = inputId,
                    onValueChange = { inputId = it },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    textStyle = TextStyle(color = Color.White),
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp),
                    decorationBox = { innerTextField ->
                        if (inputId.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_enter_id),
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        innerTextField()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if(inputId.isNotEmpty()) { onLoadOpml(inputId); inputId = "" } },
                    modifier = Modifier.size(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Check, stringResource(R.string.settings_submit))
                }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onLanguageClick,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.settings_language_section), maxLines = 1) }
            )
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { 
                    showAbout = true
                    val cached = loadAboutFromCache(context)
                    if (aboutContent == null && cached != null) {
                        aboutContent = cached
                    }

                    if (isAboutCacheFresh(context) && aboutContent != null) {
                        return@Button
                    }

                    isLoadingAbout = (aboutContent == null)
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            fetchAboutHtmlSafely()
                        }
                        if (result != null) {
                            aboutContent = result
                            saveAboutToCache(context, result)
                        }
                        isLoadingAbout = false
                    }
                },
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.settings_about_title), maxLines = 1) },
                icon = { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.cd_info)) }
            )
        }
    }
}

@Composable
fun LanguageSettingsScreen(
    selectedLanguageTag: String,
    onLanguageSelected: (String) -> Unit
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
            scrollableState = listState,
            hapticFeedbackEnabled = false
        )
    ) {
        item {
            ListHeader {
                Text(text = stringResource(R.string.settings_language_section), textAlign = TextAlign.Center)
            }
        }

        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onLanguageSelected(AppLanguageManager.LANGUAGE_ENGLISH) },
                colors = if (selectedLanguageTag == AppLanguageManager.LANGUAGE_ENGLISH) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                label = { Text(stringResource(R.string.language_english), maxLines = 1) }
            )
        }

        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onLanguageSelected(AppLanguageManager.LANGUAGE_SIMPLIFIED_CHINESE) },
                colors = if (selectedLanguageTag == AppLanguageManager.LANGUAGE_SIMPLIFIED_CHINESE) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                label = { Text(stringResource(R.string.language_simplified_chinese), maxLines = 1) }
            )
        }
    }
}

private fun normalizeHtmlBodySize(text: CharSequence): CharSequence {
    val builder = SpannableStringBuilder(text)
    builder.getSpans(0, builder.length, RelativeSizeSpan::class.java).forEach { span ->
        val start = builder.getSpanStart(span)
        val end = builder.getSpanEnd(span)
        val flags = builder.getSpanFlags(span)
        builder.removeSpan(span)
        builder.setSpan(
            RelativeSizeSpan(span.sizeChange.coerceIn(1.0f, 1.55f)),
            start,
            end,
            flags
        )
    }

    // Remove hard-coded absolute sizes but keep relative heading/body hierarchy from tags.
    builder.getSpans(0, builder.length, AbsoluteSizeSpan::class.java).forEach { span ->
        builder.removeSpan(span)
    }
    return builder
}

private const val ABOUT_URL = "https://pod.whitezaak.site/about.html"
private const val ABOUT_PREFS = "wearpod_about_cache"
private const val ABOUT_CACHE_HTML = "about_html"
private const val ABOUT_CACHE_TIME = "about_time"
private const val ABOUT_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

private fun loadAboutFromCache(context: android.content.Context): String? {
    return context.getSharedPreferences(ABOUT_PREFS, android.content.Context.MODE_PRIVATE)
        .getString(ABOUT_CACHE_HTML, null)
}

private fun isAboutCacheFresh(context: android.content.Context): Boolean {
    val prefs = context.getSharedPreferences(ABOUT_PREFS, android.content.Context.MODE_PRIVATE)
    val ts = prefs.getLong(ABOUT_CACHE_TIME, 0L)
    return ts > 0L && (System.currentTimeMillis() - ts) <= ABOUT_CACHE_TTL_MS
}

private fun saveAboutToCache(context: android.content.Context, html: String) {
    context.getSharedPreferences(ABOUT_PREFS, android.content.Context.MODE_PRIVATE).edit {
        putString(ABOUT_CACHE_HTML, html)
        putLong(ABOUT_CACHE_TIME, System.currentTimeMillis())
    }
}

private fun fetchAboutHtmlSafely(): String? {
    return try {
        val connection = (URL(ABOUT_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        val raw = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        // Remove script/style blocks so rendered content is stable and safe.
        raw.replace(Regex("<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style\\b[^<]*(?:(?!</style>)<[^<]*)*</style>", RegexOption.IGNORE_CASE), "")
    } catch (_: Exception) {
        null
    }
}