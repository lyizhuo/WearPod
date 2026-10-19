package site.whitezaak.wearpod.presentation.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
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
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
 * - NoNetwork：找网动画 + 「手表未连接 Wi-Fi」+ 两行提示 + 「打开 Wi-Fi 设置」；
 *   每 2 秒自动重试，连上即进入上传页。
 * - Running：「第 1 步 · 手机打开上传页」+ 二维码 + 地址 + 「第 2 步 · 选择 OPML 并上传」。
 * - Completed：对勾 + 「已完成」+ 本次导入统计 + 「继续导入」/「完成」。
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

    // 二维码在 ambient 下会被系统模糊成截图，扫码阶段必须保持亮屏
    SideEffect { view.keepScreenOn = state !is OfflineSessionState.Idle }

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

    ScreenListScaffold(title = stringResource(R.string.settings_offline_upload)) {
        when (state) {
            OfflineSessionState.Idle -> item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stoppedText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(),
                        label = { Text(retryLabel, maxLines = 1) },
                    )
                }
            }

            OfflineSessionState.Starting -> item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = startingText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            // 引导页：找网动画 → 标题 → 两行提示 → 打开 Wi-Fi 设置
            OfflineSessionState.NoNetwork -> {
                item {
                    WifiSearchingAnimation(modifier = Modifier.fillMaxWidth())
                }
                item {
                    Text(
                        text = noWifiTitle,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = noWifiHint,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    )
                }
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_WIFI_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(),
                        label = { Text(wifiSettingsLabel, maxLines = 1) },
                    )
                }
            }

            is OfflineSessionState.Running -> {
                item {
                    Text(
                        text = stepOpen,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
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
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                item {
                    Text(
                        text = sameWifiHint,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
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
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStop,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stopLabel, maxLines = 1) },
                    )
                }
            }

            // 结束页：对勾 → 已完成 → 本次统计 → 继续导入 / 完成
            is OfflineSessionState.Completed -> {
                item {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(DONE_ICON_SIZE),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    Text(
                        text = doneTitle,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = stringResource(
                            R.string.offline_done_summary,
                            state.result.added,
                            state.result.skipped,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = onStart,
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            label = { Text(continueLabel, maxLines = 1) },
                        )
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onStop()
                                onDone()
                            },
                            colors = ButtonDefaults.buttonColors(),
                            label = { Text(finishLabel, maxLines = 1) },
                        )
                    }
                }
            }

            OfflineSessionState.ServerFailed -> item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = serverError,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        onClick = {
                            onStop()
                            onStart()
                        },
                        colors = ButtonDefaults.buttonColors(),
                        label = { Text(retryLabel, maxLines = 1) },
                    )
                }
            }
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

/**
 * 简洁的「正在找网络」动画：圆点浮出 + 三段弧依次描边，整体呼吸循环。
 * 用 Compose Canvas 画，不引入 Lottie/AVD 资源。
 */
@Composable
private fun WifiSearchingAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wifi")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wifiProgress",
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.size(WIFI_GLYPH_SIZE)) {
        val center = Offset(size.width / 2f, size.height * 0.78f)
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        fun segment(start: Float, end: Float): Float =
            ((progress - start) / (end - start)).coerceIn(0f, 1f)

        val dotProgress = segment(0f, 0.25f)
        drawCircle(
            color = color.copy(alpha = 0.35f + 0.65f * dotProgress),
            radius = 2.5.dp.toPx() * (0.85f + 0.15f * dotProgress),
            center = center,
        )

        WIFI_ARC_WINDOWS.forEachIndexed { index, window ->
            val arcProgress = segment(window.first, window.second)
            if (arcProgress > 0f) {
                val radius = size.width * (0.22f + 0.16f * index)
                drawArc(
                    color = color.copy(alpha = 0.25f + 0.75f * arcProgress),
                    startAngle = -135f,
                    sweepAngle = 90f * arcProgress,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = stroke,
                )
            }
        }
    }
}

private const val MUTED_ALPHA = 0.6f
private const val NETWORK_RETRY_MS = 2_000L
private const val QR_PIXELS = 384
private val QR_DISPLAY_SIZE = 150.dp
private val WIFI_GLYPH_SIZE = 64.dp
private val DONE_ICON_SIZE = 40.dp
private val WIFI_ARC_WINDOWS = listOf(0.15f to 0.45f, 0.35f to 0.70f, 0.55f to 0.90f)
