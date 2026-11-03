package site.whitezaak.wearpod.presentation.screens

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import kotlinx.coroutines.delay
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.offline.LocalIpResolver
import site.whitezaak.wearpod.offline.OfflineSessionBus
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
                modifier = Modifier.fillMaxWidth().padding(top = GAP_SECTION, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(GAP_TEXT),
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
 * - NoNetwork：动画 Wi-Fi 图标 + 「手表未连接 Wi-Fi」+ 两行提示 + 「打开 Wi-Fi 设置」；每 2 秒自动重试。
 * - Running：左对齐的第 1 步、二维码、地址、第 2 步与同 Wi-Fi 提示；无停止按钮，离开即停。
 * - Completed：对勾 + 「已完成」+ 本次变更数量 + 「继续导入」/「完成」。
 * - 超时停止由服务发 sessionEnded，页面直接退出；不再有「已停止」页。
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
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val currentOnDone by rememberUpdatedState(onDone)
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    // Running 页可能已滚动过；切到 NoNetwork/Completed 时回到顶部，避免标题被裁掉
    LaunchedEffect(state) {
        listState.scrollToItem(0)
    }

    // onStart() 只投递一次 startForegroundService：服务状态要等 onStartCommand 才变。
    // 没有这个标记时，点进来又在服务起来前退出（state 还是 Idle）会漏掉停止，
    // 服务随后绑定端口并挂一块没人看的二维码，直到 5 分钟硬超时。
    var requestedStart by remember { mutableStateOf(false) }

    // 已存在的 Running/Completed 会话在熄屏后重建页面时不要重新启动；Idle/NoNetwork 才拉起服务
    LaunchedEffect(Unit) {
        when (state) {
            OfflineSessionState.Idle,
            OfflineSessionState.NoNetwork -> {
                requestedStart = true
                onStart()
            }
            else -> Unit
        }
    }

    // 服务超时（熄屏宽限/硬超时）后页面自动退出
    LaunchedEffect(Unit) {
        OfflineSessionBus.sessionEnded.collect { currentOnDone() }
    }

    DisposableEffect(Unit) {
        onDispose {
            view.keepScreenOn = false
            val current = OfflineSessionBus.state.value
            val active = current is OfflineSessionState.Starting ||
                current is OfflineSessionState.Running ||
                current is OfflineSessionState.Completed
            // 系统熄屏触发的销毁不算「用户退出」，交给服务的 screen-off 超时
            if ((active || requestedStart) && powerManager?.isInteractive != false) {
                onStop()
            }
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
                // 熄屏时后台拉起前台服务会被系统拒绝，等用户点亮屏幕再试
                if (powerManager?.isInteractive == false) continue
                if (LocalIpResolver.resolve(context) != null) {
                    requestedStart = true
                    onStart()
                    // 不 break：服务侧可能仍判定无网（两次探测之间网络抖了一下），
                    // 那样状态还停在 NoNetwork，这里必须继续重试，否则页面会卡死。
                }
            }
        }
    }

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
    val retryLabel = stringResource(R.string.offline_retry)
    val doneTitle = stringResource(R.string.offline_done_title)
    val continueLabel = stringResource(R.string.offline_continue_import)
    val finishLabel = stringResource(R.string.offline_finish_action)

    // 结束页文案：只列出真实发生的变更，避免「删除 0 个」这种废话
    val doneSummary: String? = (state as? OfflineSessionState.Completed)?.summary?.let { summary ->
        val lines = buildList {
            if (summary.added > 0) {
                add(pluralStringResource(R.plurals.offline_done_imported, summary.added, summary.added))
            }
            if (summary.deleted > 0) {
                add(pluralStringResource(R.plurals.offline_done_deleted, summary.deleted, summary.deleted))
            }
            if (summary.skipped > 0) {
                add(pluralStringResource(R.plurals.offline_done_skipped, summary.skipped, summary.skipped))
            }
            if (isEmpty()) add(stringResource(R.string.offline_done_nothing))
        }
        lines.joinToString("\n")
    }

    ScreenListScaffold(
        title = stringResource(R.string.settings_import_opml),
        listState = listState,
    ) {
        when (state) {
            OfflineSessionState.Idle,
            OfflineSessionState.Starting -> item {
                StartingPage(text = startingText)
            }

            OfflineSessionState.NoNetwork -> item {
                WifiGuidePage(
                    title = noWifiTitle,
                    hint = noWifiHint,
                    buttonLabel = wifiSettingsLabel,
                    onOpenWifiSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_WIFI_SETTINGS)
                            )
                        }
                    },
                )
            }

            is OfflineSessionState.Running -> item {
                RunningPage(
                    pageUrl = state.pageUrl,
                    stepOpen = stepOpen,
                    qrHint = pageQrHint,
                    stepUpload = stepUpload,
                    sameWifiHint = sameWifiHint,
                    keepOpen = keepOpen,
                )
            }

            is OfflineSessionState.Completed -> item {
                CompletedPage(
                    title = doneTitle,
                    summary = doneSummary.orEmpty(),
                    continueLabel = continueLabel,
                    finishLabel = finishLabel,
                    onContinue = {
                        requestedStart = true
                        onStart()
                    },
                    onFinish = {
                        onStop()
                        onDone()
                    },
                )
            }

            OfflineSessionState.ServerFailed -> item {
                ServerFailedPage(
                    title = serverError,
                    retryLabel = retryLabel,
                    onRetry = {
                        requestedStart = true
                        onStart()
                    },
                )
            }
        }
    }
}

@Composable
private fun StartingPage(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP_TEXT),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = MUTED_ALPHA),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Wi-Fi 引导页：动画图标 → 状态 → 两行提示 → 主按钮，按 Figma 居中排布。 */
@Composable
private fun WifiGuidePage(
    title: String,
    hint: String,
    buttonLabel: String,
    onOpenWifiSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WifiGuideIcon(modifier = Modifier.size(ICON_SIZE))
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_TEXT))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(0.62f).height(43.dp),
            onClick = onOpenWifiSettings,
            colors = ButtonDefaults.buttonColors(),
            label = {
                Text(
                    buttonLabel,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )
    }
}

/** 完成页：对勾 → 已完成 → 变更摘要 → 继续导入/完成；两个按钮同用 tonal，和 Figma 一致。 */
@Composable
private fun CompletedPage(
    title: String,
    summary: String,
    continueLabel: String,
    finishLabel: String,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_offline_check),
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.height(GAP_SECTION))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_TEXT))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_COMPLETED_BUTTONS))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            FigmaActionButton(label = continueLabel, onClick = onContinue)
            FigmaActionButton(label = finishLabel, onClick = onFinish)
        }
    }
}

/** Figma 规格的操作按钮：84×43、10dp 圆角、#3B3B3B、15sp 白字居中。 */
@Composable
private fun FigmaActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(37.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF3B3B3B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 服务启动失败：图标 → 说明 → 重试。 */
@Composable
private fun ServerFailedPage(
    title: String,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(GAP_SECTION))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_SECTION))
        Button(
            modifier = Modifier.fillMaxWidth().height(43.dp),
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(),
            label = { Text(retryLabel, maxLines = 1) },
        )
    }
}

/** 扫码上传页：所有内容左对齐，二维码与文字共享同一条左边缘；没有停止按钮。 */
@Composable
private fun RunningPage(
    pageUrl: String,
    stepOpen: String,
    qrHint: String,
    stepUpload: String,
    sameWifiHint: String,
    keepOpen: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stepOpen,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_BLOCK))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            QrImage(payload = pageUrl, contentDescription = qrHint)
        }
        Spacer(Modifier.height(GAP_BLOCK))
        Text(
            text = stringResource(R.string.offline_page_url, pageUrl),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_SECTION))
        Text(
            text = stepUpload,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_TEXT))
        Text(
            text = sameWifiHint,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GAP_BLOCK))
        Text(
            text = keepOpen,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
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
private val QR_DISPLAY_SIZE = 132.dp
private val ICON_SIZE = 32.dp
private val GAP_TEXT = 4.dp
private val GAP_BLOCK = 8.dp
private val GAP_SECTION = 12.dp
private val GAP_COMPLETED_BUTTONS = 4.dp
