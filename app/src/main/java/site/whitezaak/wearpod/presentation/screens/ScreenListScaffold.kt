package site.whitezaak.wearpod.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.ui.unit.dp

@Composable
fun ScreenListScaffold(
    title: String,
    modifier: Modifier = Modifier,
    listState: ScalingLazyListState = rememberScalingLazyListState(initialCenterItemIndex = 0),
    content: ScalingLazyListScope.() -> Unit,
) {
    val titleContentPadding = PaddingValues(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 12.dp)

    ScreenScaffold(scrollState = listState, modifier = modifier.fillMaxSize()) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val listContentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding() + 28.dp, // 列表底部padding，防止圆形屏幕底切
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
                    Text(
                        text = title,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            content()
        }
    }
}
