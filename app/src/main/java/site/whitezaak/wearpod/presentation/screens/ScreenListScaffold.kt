package site.whitezaak.wearpod.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun ScreenListScaffold(
    title: String,
    modifier: Modifier = Modifier,
    listState: ScalingLazyListState = rememberScalingLazyListState(initialCenterItemIndex = 0),
    titleTrailing: (@Composable () -> Unit)? = null,
    content: ScalingLazyListScope.() -> Unit,
) {
    val titleContentPadding = PaddingValues(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 12.dp)
    val gapPx = with(LocalDensity.current) { 7.dp.roundToPx() } // 呼吸灯 离title的相对位置偏移
    var titleWidthPx by remember { mutableIntStateOf(0) }

    ScreenScaffold(scrollState = listState, modifier = modifier.fillMaxSize()) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val listContentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        )

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = listContentPadding,
            anchorType = ScalingLazyListAnchorType.ItemStart,
            autoCentering = null,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(
                scrollableState = listState,
                hapticFeedbackEnabled = false,
            ),
        ) {
            item(key = "page_title") {
                ListHeader(contentPadding = titleContentPadding) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Title text — always absolutely centered
                        Text(
                            text = title,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.onGloballyPositioned {
                                titleWidthPx = it.size.width
                            },
                        )
                        // Trailing content — offset to the right of the title
                        if (titleTrailing != null && titleWidthPx > 0) {
                            Box(
                                modifier = Modifier.offset {
                                    IntOffset(x = titleWidthPx / 2 + gapPx, y = 1)
                                }
                            ) {
                                titleTrailing()
                            }
                        }
                    }
                }
            }
            content()
        }
    }
}
