package site.whitezaak.wearpod.presentation.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.offline.ErrorKind
import site.whitezaak.wearpod.offline.OfflineSessionState
import site.whitezaak.wearpod.offline.QrCodeGenerator
import site.whitezaak.wearpod.offline.QrPayloads
import site.whitezaak.wearpod.settings.SubscriptionSource

/** 「订阅来源」选择页：同步站（5 位码）或手表本地（热点上传）。 */
@Composable
fun SubscriptionSourceSettingsScreen(
    currentSource: SubscriptionSource,
    onSourceSelected: (SubscriptionSource) -> Unit,
) {
    ScreenListScaffold(title = stringResource(R.string.settings_subscription_source)) {
        item {
            SourceOptionRow(
                label = stringResource(R.string.subscription_source_remote),
                description = stringResource(R.string.subscription_source_remote_desc),
                selected = currentSource == SubscriptionSource.REMOTE,
                onClick = { onSourceSelected(SubscriptionSource.REMOTE) },
            )
        }
        item {
            SourceOptionRow(
                label = stringResource(R.string.subscription_source_local),
                description = stringResource(R.string.subscription_source_local_desc),
                selected = currentSource == SubscriptionSource.LOCAL,
                onClick = { onSourceSelected(SubscriptionSource.LOCAL) },
            )
        }
    }
}

@Composable
private fun SourceOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            colors = if (selected) {
                ButtonDefaults.buttonColors()
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
            icon = if (selected) {
                { Icon(Icons.Filled.Check, contentDescription = null) }
            } else {
                null
            },
            label = { Text(label, maxLines = 1) },
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 2.dp),
        )
    }
}

/** 离线上传页：进入即拉起热点 + 本地服务，退出即释放。 */
@Composable
fun OfflineUploadScreen(
    state: OfflineSessionState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val permission = remember { hotspotPermission() }
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) onStart()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (granted) onStart() else launcher.launch(permission)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            view.keepScreenOn = false
            onStop()
        }
    }

    // 二维码在 ambient 下会被系统模糊成截图，扫码阶段必须保持亮屏
    SideEffect { view.keepScreenOn = state !is OfflineSessionState.Idle }

    val startingText = stringResource(R.string.offline_starting)
    val stoppedText = stringResource(R.string.offline_stopped)
    val stepConnect = stringResource(R.string.offline_step_connect)
    val stepOpen = stringResource(R.string.offline_step_open)
    val keepOpen = stringResource(R.string.offline_keep_open)
    val lanHint = stringResource(R.string.offline_lan_hint)
    val wifiQrHint = stringResource(R.string.offline_qr_wifi_hint)
    val pageQrHint = stringResource(R.string.offline_qr_url_hint)
    val stopLabel = stringResource(R.string.offline_stop)
    val retryLabel = stringResource(R.string.offline_retry)
    val wifiSettingsLabel = stringResource(R.string.offline_open_wifi_settings)

    ScreenListScaffold(title = stringResource(R.string.settings_offline_upload)) {
        when (state) {
            OfflineSessionState.Idle -> item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stoppedText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                onStart()
                            } else {
                                launcher.launch(permission)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(),
                        label = { Text(retryLabel, maxLines = 1) },
                    )
                }
            }

            OfflineSessionState.Starting -> item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = startingText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            is OfflineSessionState.Running -> {
                if (state.ssid != null) {
                    item {
                        Text(
                            text = stepConnect,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        QrImage(
                            payload = QrPayloads.wifi(state.ssid, state.passphrase),
                            contentDescription = wifiQrHint,
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.offline_ssid, state.ssid),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!state.passphrase.isNullOrEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.offline_passphrase, state.passphrase),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            text = lanHint,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Text(
                        text = stepOpen,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                item {
                    QrImage(
                        payload = state.pageUrl,
                        contentDescription = pageQrHint,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.offline_page_url, state.pageUrl),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.lastImport?.let { result ->
                    item {
                        Text(
                            text = stringResource(R.string.offline_last_import, result.added, result.total),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Text(
                        text = keepOpen,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
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

            is OfflineSessionState.Error -> {
                item {
                    Text(
                        text = errorMessage(state.kind),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
                if (state.kind == ErrorKind.WIFI_DISABLED) {
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
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                onStart()
                            } else {
                                launcher.launch(permission)
                            }
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

@Composable
private fun errorMessage(kind: ErrorKind): String = when (kind) {
    ErrorKind.PERMISSION_DENIED -> stringResource(R.string.offline_error_permission)
    ErrorKind.WIFI_DISABLED -> stringResource(R.string.offline_error_wifi_disabled)
    ErrorKind.HOTSPOT_FAILED -> stringResource(R.string.offline_error_hotspot)
    ErrorKind.SERVER_FAILED -> stringResource(R.string.offline_error_server)
    ErrorKind.NO_ADDRESS -> stringResource(R.string.offline_error_no_address)
}

private fun hotspotPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

private const val QR_PIXELS = 384
private val QR_DISPLAY_SIZE = 150.dp
