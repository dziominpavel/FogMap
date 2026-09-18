package ru.fogmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Маска тумана Canvas (fog-mask-canvas): глухая вуаль первым кадром
 * + мягкие скругленные дырки. Рамок нет, просвечивания нет (fail-closed).
 *
 * Перо — градиентом на границе дырки ([BlendMode.DstOut] кольцами),
 * не блюром всего экрана: дешево на пане. Дырки уже спроецированы
 * слоем в [FogMask.HolePx]; здесь только пиксели.
 */
@Composable
fun FogMaskOverlay(
    holes: List<FogMask.HolePx>,
    veilColor: Color,
    featherPx: Float = FogMask.FEATHER_PX,
    cornerPx: Float = FogMask.CORNER_PX,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier
            .graphicsLayer {
                // Offscreen нужен DstOut: иначе стирание бьет по карте под Canvas.
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        drawRect(veilColor)
        for (h in holes) {
            val w = h.right - h.left
            val hgt = h.bottom - h.top
            if (w <= 0f || hgt <= 0f) continue
            // Перо снаружи внутрь: широкие бледные кольца, затем глухое ядро.
            for ((expand, alpha) in featherRings(featherPx)) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = alpha),
                    blendMode = BlendMode.DstOut,
                    topLeft = Offset(h.left - expand, h.top - expand),
                    size = Size(w + expand * 2f, hgt + expand * 2f),
                    cornerRadius = CornerRadius(cornerPx + expand, cornerPx + expand)
                )
            }
            drawRoundRect(
                color = Color.Black,
                blendMode = BlendMode.Clear,
                topLeft = Offset(h.left, h.top),
                size = Size(w, hgt),
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
        }
    }
}

/** Кольца пера: (расширение наружу, alpha стирания). Широкое и мягкое. */
internal fun featherRings(featherPx: Float): List<Pair<Float, Float>> = listOf(
    featherPx to 0.25f,
    featherPx * 0.66f to 0.45f,
    featherPx * 0.33f to 0.65f
)
