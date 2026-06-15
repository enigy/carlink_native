package com.carlink.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

// ── Tunables ────────────────────────────────────────────────────────────────
// Fraction of screen HEIGHT the fog band occupies, anchored at the BOTTOM.
private const val FOG_BAND_FRACTION = 0.20f

// Milliseconds for the near layer to drift one full width ("mediocre pace").
// Larger = slower. The far layer uses 1.7x this for parallax.
private const val FOG_CYCLE_MS = 32000

private const val PUFFS_PER_LAYER = 9

// Warm pale tints so the fog blends with the sunset image (not stark white).
private val FAR_TINT = Color(0xFFEFDCD8)
private val NEAR_TINT = Color(0xFFF4E3DF)
// ────────────────────────────────────────────────────────────────────────────

private data class FogPuff(
    val baseXFrac: Float,   // 0..1 start position across the (wrapped) width
    val yFrac: Float,       // 0..1 within the fog band (0 = band top, 1 = bottom)
    val radiusFrac: Float,  // radius as a fraction of screen height
    val alpha: Float,
)

private fun genPuffs(
    yMin: Float, yMax: Float,
    rMin: Float, rMax: Float,
    aMin: Float, aMax: Float,
): List<FogPuff> = List(PUFFS_PER_LAYER) {
    FogPuff(
        baseXFrac = Random.nextFloat(),
        yFrac = yMin + Random.nextFloat() * (yMax - yMin),
        radiusFrac = rMin + Random.nextFloat() * (rMax - rMin),
        alpha = aMin + Random.nextFloat() * (aMax - aMin),
    )
}

/**
 * Dynamic drifting fog for the connecting overlay — replaces the sparkle particles.
 *
 * Draws two parallax layers of soft, semi-transparent radial "puffs" that scroll horizontally
 * and wrap seamlessly, confined to the bottom [FOG_BAND_FRACTION] of the screen (over the forest
 * in the background image). The near layer drifts at [FOG_CYCLE_MS]; the far layer is slower and
 * fainter for depth. Each puff fades toward the top of the band so the fog settles at the bottom
 * and blends up into the image. A faint static base gradient anchors the very bottom.
 *
 * Transparent canvas — the background image composes behind, icon + status text on top.
 * Seamless wrap: each layer drifts exactly one [span] per cycle, so phase 1 == phase 0.
 */
@Composable
fun ForestFogEffect(modifier: Modifier = Modifier) {
    val farPuffs = remember { genPuffs(0.20f, 0.65f, 0.15f, 0.26f, 0.04f, 0.09f) }
    val nearPuffs = remember { genPuffs(0.50f, 1.00f, 0.20f, 0.34f, 0.07f, 0.14f) }

    val transition = rememberInfiniteTransition(label = "forest_fog")
    val phaseNear by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(FOG_CYCLE_MS, easing = LinearEasing)),
        label = "fog_near",
    )
    val phaseFar by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((FOG_CYCLE_MS * 1.7f).toInt(), easing = LinearEasing)),
        label = "fog_far",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val bandTop = h * (1f - FOG_BAND_FRACTION)
        val bandH = h * FOG_BAND_FRACTION

        // Static settled-fog base at the very bottom.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, NEAR_TINT.copy(alpha = 0.16f)),
                startY = bandTop,
                endY = h,
            ),
            topLeft = Offset(0f, bandTop),
            size = Size(w, bandH),
        )

        drawFogLayer(farPuffs, FAR_TINT, phaseFar, w, bandTop, bandH)
        drawFogLayer(nearPuffs, NEAR_TINT, phaseNear, w, bandTop, bandH)
    }
}

private fun DrawScope.drawFogLayer(
    puffs: List<FogPuff>,
    tint: Color,
    phase: Float,
    w: Float,
    bandTop: Float,
    bandH: Float,
) {
    puffs.forEach { p ->
        val r = p.radiusFrac * (bandH / FOG_BAND_FRACTION) // radius relative to full height
        val span = w + 2f * r
        val x = (p.baseXFrac * span + phase * span) % span - r
        val y = bandTop + p.yFrac * bandH
        // Fade puffs toward the band top so fog is densest at the bottom and dissolves upward.
        val vFade = ((y - bandTop) / bandH).coerceIn(0f, 1f)
        val a = (p.alpha * (0.30f + 0.70f * vFade)).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = a), Color.Transparent),
                center = Offset(x, y),
                radius = r,
            ),
            radius = r,
            center = Offset(x, y),
        )
    }
}
