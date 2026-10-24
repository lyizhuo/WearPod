package site.whitezaak.wearpod.presentation.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.offline.LocalIpResolver
import site.whitezaak.wearpod.offline.OfflineSessionState
import site.whitezaak.wearpod.offline.QrCodeGenerator
import site.whitezaak.wearpod.settings.SubscriptionSource

/** 「订阅来源」选择页：云端同步（5 位码）或手表本地（同 Wi-Fi 上传）。选中只用按钮颜色表达，不用图标。 */
@Composable
fun SubscriptionSourceSettingsScreen(
    currentSource: SubscriptionSource,
    onSourceSelected: (SubscriptionSource) -> Unit,
) {
    val cloudLabel = stringResource(R.string.subscription_source_cloud)
    val localLabel = stringResource(R.string.subscription_source_local)
    val cloudLine = stringResource(
        R.string.subscription_source_desc_line,
        cloudLabel,
        stringResource(R.string.subscription_source_cloud_desc),
    )
    val localLine = stringResource(
        R.string.subscription_source_desc_line,
        localLabel,
        stringResource(R.string.subscription_source_local_desc),
    )

    ScreenListScaffold(title = stringResource(R.string.settings_subscription_source)) {
        item {
            SourceButton(
                label = cloudLabel,
                selected = currentSource == SubscriptionSource.REMOTE,
                onClick = { onSourceSelected(SubscriptionSource.REMOTE) },
            )
        }
        item {
            SourceButton(
                label = localLabel,
                selected = currentSource == SubscriptionSource.LOCAL,
                onClick = { onSourceSelected(SubscriptionSource.LOCAL) },
            )
        }
        // 说明统一放在所有选项之后，左对齐
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = GAP_GROUP, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(GAP_INNER),
            ) {
                Text(
                    text = cloudLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = localLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SourceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        label = { Text(label, maxLines = 1) },
    )
}

/**
 * 本地上传页：进入即启动局域网服务，退出即释放。
 *
 * 设备测出无法开热点，因此只用「手机与手表同一 Wi-Fi」一条路径。状态与版式：
 * - NoNetwork：「手表未连接 Wi-Fi」+ 两行提示 + 「打开 Wi-Fi 设置」；每 2 秒自动重试。
 * - Running：「第 1 步 · 手机打开上传页」+ 二维码 + 地址 + 「第 2 步 · 选择 OPML 并上传」。
 * - Completed：对勾 + 「已完成」+ 本次变更数量（导入/删除/跳过）+ 「继续导入」/「完成」。
 */
@Composable
fun OfflineUploadScreen(
    state: OfflineSessionState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(Unit) { onStart() }

    DisposableEffect(Unit) {
        onDispose {
            view.keepScreenOn = false
            onStop()
        }
    }

    // 只有需要展示二维码/地址的 Starting/Running 需要保持亮屏；NoNetwork 页面
    // 没有二维码，且服务已停止、没有硬超时，若继续 keepScreenOn 会无限期亮屏。
    SideEffect {
        view.keepScreenOn = state is OfflineSessionState.Starting ||
            state is OfflineSessionState.Running
    }

    // 未联网时：每 2 秒探测一次局域网地址，一旦有 Wi-Fi/以太网地址立即重新拉起会话
    LaunchedEffect(state) {
        if (state is OfflineSessionState.NoNetwork) {
            while (true) {
                delay(NETWORK_RETRY_MS)
                if (LocalIpResolver.resolve(context) != null) {
                    onStart()
                    break
                }
            }
        }
    }

    val stoppedText = stringResource(R.string.offline_stopped)
    val restartLabel = stringResource(R.string.offline_restart)
    val startingText = stringResource(R.string.offline_starting)
    val noWifiTitle = stringResource(R.string.offline_no_network_title)
    val noWifiHint = stringResource(R.string.offline_no_network_hint)
    val wifiSettingsLabel = stringResource(R.string.offline_open_wifi_settings)
    val stepOpen = stringResource(R.string.offline_step_open)
    val pageQrHint = stringResource(R.string.offline_qr_url_hint)
    val stepUpload = stringResource(R.string.offline_step_upload)
    val sameWifiHint = stringResource(R.string.offline_same_wifi_hint)
    val keepOpen = stringResource(R.string.offline_keep_open)
    val serverError = stringResource(R.string.offline_error_server)
    val stopLabel = stringResource(R.string.offline_stop)
    val retryLabel = stringResource(R.string.offline_retry)
    val doneTitle = stringResource(R.string.offline_done_title)
    val continueLabel = stringResource(R.string.offline_continue_import)
    val finishLabel = stringResource(R.string.offline_finish_action)

    // 结束页文案：只列出真实发生的变更，避免「删除 0 个」这种废话
    val doneSummary: String? = (state as? OfflineSessionState.Completed)?.summary?.let { summary ->
        val lines = buildList {
            if (summary.added > 0) add(stringResource(R.string.offline_done_imported, summary.added))
            if (summary.deleted > 0) add(stringResource(R.string.offline_done_deleted, summary.deleted))
            if (summary.skipped > 0) add(stringResource(R.string.offline_done_skipped, summary.skipped))
            if (isEmpty()) add(stringResource(R.string.offline_done_nothing))
        }
        lines.joinToString("\n")
    }

    ScreenListScaffold(title = stringResource(R.string.settings_offline_upload)) {
        when (state) {
            OfflineSessionState.Idle -> item {
                MessagePage(
                    icon = Icons.Filled.StopCircle,
                    title = stoppedText,
                    body = null,
                    primaryLabel = restartLabel,
                    onPrimary = onStart,
                )
            }

            OfflineSessionState.Starting -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = GAP_GROUP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GAP_INNER),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = startingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            OfflineSessionState.NoNetwork -> item {
                MessagePage(
                    icon = null,
                    title = noWifiTitle,
                    body = noWifiHint,
                    primaryLabel = wifiSettingsLabel,
                    onPrimary = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_WIFI_SETTINGS)
                            )
                        }
                    },
                )
            }

            is OfflineSessionState.Running -> {
                item {
                    Text(
                        text = stepOpen,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = GAP_GROUP),
                    )
                }
                item {
                    QrImage(payload = state.pageUrl, contentDescription = pageQrHint)
                }
                item {
                    Text(
                        text = stringResource(R.string.offline_page_url, state.pageUrl),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = stepUpload,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = GAP_GROUP),
                    )
                }
                item {
                    Text(
                        text = sameWifiHint,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = keepOpen,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = GAP_GROUP),
                        onClick = onStop,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stopLabel, maxLines = 1) },
                    )
                }
            }

            is OfflineSessionState.Completed -> item {
                MessagePage(
                    icon = Icons.Filled.CheckCircle,
                    title = doneTitle,
                    body = doneSummary,
                    primaryLabel = finishLabel,
                    onPrimary = {
                        onStop()
                        onDone()
                    },
                    secondaryLabel = continueLabel,
                    onSecondary = onStart,
                )
            }

            OfflineSessionState.ServerFailed -> item {
                MessagePage(
                    icon = Icons.Filled.ErrorOutline,
                    title = serverError,
                    body = null,
                    primaryLabel = retryLabel,
                    onPrimary = {
                        onStop()
                        onStart()
                    },
                )
            }
        }
    }
}

/** 状态页统一版式：可选图标 → 标题 → 说明 → 一或两个按钮。间距按 Wear M3 的 4/12dp 节奏。 */
@Composable
private fun MessagePage(
    icon: ImageVector?,
    title: String,
    body: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = GAP_GROUP),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP_INNER),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(STATUS_ICON_SIZE),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            )
        }
        if (secondaryLabel != null && onSecondary != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = GAP_INNER),
                horizontalArrangement = Arrangement.spacedBy(GAP_ROW),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onSecondary,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(secondaryLabel, maxLines = 1) },
                )
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onPrimary,
                    colors = ButtonDefaults.buttonColors(),
                    label = { Text(primaryLabel, maxLines = 1) },
                )
            }
        } else {
            Button(
                modifier = Modifier.fillMaxWidth().padding(top = GAP_INNER),
                onClick = onPrimary,
                colors = ButtonDefaults.buttonColors(),
                label = { Text(primaryLabel, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun QrImage(payload: String, contentDescription: String) {
    val bitmap = remember(payload) { QrCodeGenerator.bitmap(payload, QR_PIXELS) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = Modifier.size(QR_DISPLAY_SIZE),
    )
}

private const val MUTED_ALPHA = 0.6f
private const val NETWORK_RETRY_MS = 2_000L
private const val QR_PIXELS = 384
private val QR_DISPLAY_SIZE = 150.dp
private val STATUS_ICON_SIZE = 40.dp
private val GAP_GROUP = 12.dp
private val GAP_INNER = 4.dp
private val GAP_ROW = 8.dp
