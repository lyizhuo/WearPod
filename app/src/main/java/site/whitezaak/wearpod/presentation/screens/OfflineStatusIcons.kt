package site.whitezaak.wearpod.presentation.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.wear.compose.material3.MaterialTheme

/** Figma 2012:343 的 Wi-Fi 图标：浅色圆 + 背景色镂空信号波，内圈先亮、外圈后亮。 */
@Composable
fun WifiGuideIcon(modifier: Modifier = Modifier) {
    val circle = remember { PathParser().parsePathString(WIFI_CIRCLE).toPath() }
    val dot = remember { PathParser().parsePathString(WIFI_DOT).toPath() }
    val innerArc = remember { PathParser().parsePathString(WIFI_INNER_ARC).toPath() }
    val outerArc = remember { PathParser().parsePathString(WIFI_OUTER_ARC).toPath() }

    val transition = rememberInfiniteTransition(label = "wifi-search")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wifi-phase",
    )
    val innerAlpha = wifiPulse(phase, offset = 0f)
    val outerAlpha = wifiPulse(phase, offset = -0.15f)
    val glyphColor = MaterialTheme.colorScheme.background

    Canvas(modifier = modifier) {
        val scale = size.width / 35f
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            drawPath(circle, ICON_TINT)
            drawPath(dot, glyphColor)
            drawPath(innerArc, glyphColor, alpha = innerAlpha)
            drawPath(outerArc, glyphColor, alpha = outerAlpha)
        }
    }
}

private fun wifiPulse(phase: Float, offset: Float): Float {
    val x = (phase + offset + 1f) % 1f
    return when {
        x < 0.18f -> 0.15f + (x / 0.18f) * 0.85f
        x < 0.62f -> 1f
        x < 0.80f -> 1f - ((x - 0.62f) / 0.18f) * 0.85f
        else -> 0.15f
    }
}

private val ICON_TINT = Color(0xFFEFF0EF)

private const val WIFI_CIRCLE =
    "M17.5 0C7.83499 0 0 7.83499 0 17.5C0 27.165 7.83499 35 17.5 35C27.165 35 35 27.165 35 17.5C35 7.83499 27.165 0 17.5 0Z"
private const val WIFI_DOT =
    "M18.7535 25.1509C18.4204 25.4836 17.9688 25.6704 17.4981 25.6701C17.0273 25.6699 16.5759 25.4827 16.2432 25.1496L16.2419 25.1483C16.0752 24.9839 15.9427 24.788 15.8521 24.5721C15.7614 24.3562 15.7143 24.1245 15.7136 23.8903C15.7129 23.6561 15.7585 23.4241 15.8479 23.2077C15.9372 22.9912 16.0685 22.7945 16.2342 22.629C16.3998 22.4635 16.5966 22.3324 16.8132 22.2433C17.0297 22.1542 17.2618 22.1088 17.496 22.1098C17.7301 22.1107 17.9618 22.158 18.1776 22.2489C18.3934 22.3398 18.5892 22.4725 18.7535 22.6393L18.7547 22.6406C19.0874 22.9736 19.2742 23.4252 19.274 23.896C19.2737 24.3667 19.0865 24.8181 18.7535 25.1509Z"
private const val WIFI_INNER_ARC =
    "M23.1595 19.9806C20.032 16.8593 14.9678 16.8593 11.8403 19.9806L10.574 18.7263C14.3931 14.9095 20.5827 14.9095 24.4018 18.7263L23.1595 19.9806Z"
private const val WIFI_OUTER_ARC =
    "M26.55 14.8447C21.5478 9.85628 13.4521 9.85628 8.45004 14.8447L7.19573 13.5904C12.891 7.90969 22.1092 7.90969 27.8043 13.5904L26.55 14.8447Z"
