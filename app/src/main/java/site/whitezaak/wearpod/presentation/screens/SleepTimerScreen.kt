package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.presentation.SleepTimerMode

@Composable
fun SleepTimerScreen(
    currentTimerMode: SleepTimerMode,
    currentTimerRemainingMs: Long?,
    onSelectTimer: (SleepTimerMode) -> Unit
) {
    val options = SleepTimerMode.entries

    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    // 【核心修复 1】定义列表状态
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        // 【核心修复 2】绑定状态
        state = listState,
        // 【核心修复 3】显式禁用导致闪退的震动反馈
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
            scrollableState = listState,
            hapticFeedbackEnabled = false
        )
    ) {
        item {
            ListHeader {
                Text(text = stringResource(R.string.sleep_timer_title), textAlign = TextAlign.Center)
            }
        }

        if (currentTimerMode.minutes > 0 && currentTimerRemainingMs != null) {
            item {
                Text(
                    text = stringResource(
                        R.string.sleep_timer_time_left,
                        formatRemaining(currentTimerRemainingMs)
                    ),
                    maxLines = 1
                )
            }
        }

        items(options) { option ->
            val isSelected = currentTimerMode == option
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelectTimer(option) },
                colors = if (isSelected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
            ) {
                Text(
                    text = when (option) {
                        SleepTimerMode.Off -> stringResource(R.string.sleep_timer_off)
                        SleepTimerMode.In15Minutes -> stringResource(R.string.sleep_timer_15_minutes)
                        SleepTimerMode.In30Minutes -> stringResource(R.string.sleep_timer_30_minutes)
                        SleepTimerMode.In45Minutes -> stringResource(R.string.sleep_timer_45_minutes)
                        SleepTimerMode.InOneHour -> stringResource(R.string.sleep_timer_one_hour)
                        SleepTimerMode.EndOfEpisode -> stringResource(R.string.sleep_timer_end_of_episode)
                    },
                    maxLines = 1
                )
            }
        }
    }
}