package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
// 核心修复相关的导入
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text

@Composable
fun SleepTimerScreen(
    currentTimerMode: String,
    onSelectTimer: (String, Int) -> Unit
) {
    val options = listOf(
        "Off" to 0,
        "In 15 Minutes" to 15,
        "In 30 Minutes" to 30,
        "In 45 Minutes" to 45,
        "In One Hour" to 60,
        "When Current Episode Ends" to -1
    )

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
                Text(text = "Sleep Timer", textAlign = TextAlign.Center)
            }
        }

        items(options) { (label, value) ->
            val isSelected = currentTimerMode == label
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelectTimer(label, value) },
                colors = if (isSelected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
            ) {
                Text(text = label, maxLines = 1)
            }
        }
    }
}