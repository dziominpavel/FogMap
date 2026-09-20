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
 *
 * Грубые пятна присутствия ([FogMask.HolePx.coarse], smooth-fog-zoom)
 * рисуются с увеличенным скруглением и пером от размера дырки:
 * константа 6px незаметна на пятне 360 м (острые углы сетки),
 * а четверть меньшей стороны скругляет ступени в мягкие пятна.
 * Точные дырки — как раньше ([FogMask.CORNER_PX]/[FogMask.FEATHER_PX]).
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
            val corner = cornerPxFor(w, hgt, h.coarse, cornerPx)
            val feather = featherPxFor(h.coarse, featherPx)
            // Перо снаружи внутрь: широкие бледные кольца, затем глухое ядро.
            for ((expand, alpha) in featherRings(feather)) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = alpha),
                    blendMode = BlendMode.DstOut,
                    topLeft = Offset(h.left - expand, h.top - expand),
                    size = Size(w + expand * 2f, hgt + expand * 2f),
                    cornerRadius = CornerRadius(corner + expand, corner + expand)
                )
            }
            drawRoundRect(
                color = Color.Black,
                blendMode = BlendMode.Clear,
                topLeft = Offset(h.left, h.top),
                size = Size(w, hgt),
                cornerRadius = CornerRadius(corner, corner)
            )
        }
    }
}

/**
 * Скругление дырки: точным — константа, грубым пятнам — половина меньшей
 * стороны («подушки»: ступени сетки не читаются острыми углами).
 * Чистая, unit-тестируема.
 */
internal fun cornerPxFor(w: Float, hgt: Float, coarse: Boolean, cornerPx: Float): Float =
    if (!coarse) cornerPx else maxOf(cornerPx, minOf(w, hgt) * COARSE_CORNER_FRAC)

/** Доля меньшей стороны дырки для скругления грубых пятен. */
internal const val COARSE_CORNER_FRAC = 0.5f

/**
 * Перо дырки: точным — константа, грубым пятнам — двойное для мягкого края.
 * Чистая, unit-тестируема.
 */
internal fun featherPxFor(coarse: Boolean, featherPx: Float): Float =
    if (!coarse) featherPx else featherPx * COARSE_FEATHER_MULT

/** Множитель пера грубых пятен присутствия. */
internal const val COARSE_FEATHER_MULT = 2.0f

/** Кольца пера: (расширение наружу, alpha стирания). Широкое и мягкое. */
internal fun featherRings(featherPx: Float): List<Pair<Float, Float>> = listOf(
    featherPx to 0.25f,
    featherPx * 0.66f to 0.45f,
    featherPx * 0.33f to 0.65f
)
