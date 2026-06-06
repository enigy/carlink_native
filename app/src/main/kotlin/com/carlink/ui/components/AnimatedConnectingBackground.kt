package com.carlink.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

// Number of concentric rings that pulse outward simultaneously (staggered evenly)
private const val RING_COUNT = 4

// How long one full ring cycle takes — halved from original 3200 ms for a
// slower, more relaxed feel.
private const val RING_CYCLE_MS = 6400

// Scattered background particles for depth
private const val PARTICLE_COUNT = 48

private data class Particle(
    val xFrac: Float,   // 0..1 fraction of canvas width
    val yFrac: Float,   // 0..1 fraction of canvas height
    val phase: Float,   // 0..1 animation phase offset so particles twinkle independently
    val radiusPx: Float,
)

/**
 * Fullscreen animated background for the adapter/phone connecting phase.
 *
 * Layers (back to front):
 *  1. Deep dark radial gradient — navy core fading to near-black at edges
 *  2. Scattered particles — tiny dots that twinkle in/out independently
 *  3. Concentric rings — [RING_COUNT] rings that expand from the screen centre
 *     and fade as they grow, staggered so one is always visible.
 *     Rings are drawn with an antialiased [Paint] via [drawIntoCanvas] so
 *     edges stay smooth at all radii and DPI settings.
 *  4. Breathing centre glow — a soft radial spot that pulses slowly
 *
 * All animation is driven by [rememberInfiniteTransition]; no coroutines needed.
 * Canvas is the only drawing surface — no AndroidView interop, no bitmaps.
 */
@Composable
fun AnimatedConnectingBackground(modifier: Modifier = Modifier) {
    // Fixed random particles — regenerated only if the composable is fully disposed
    val particles = remember {
        List(PARTICLE_COUNT) {
            Particle(
                xFrac = Random.nextFloat(),
                yFrac = Random.nextFloat(),
                phase = Random.nextFloat(),
                radiusPx = Random.nextFloat() * 2.2f + 0.8f,
            )
        }
    }

    // Reusable Paint object for antialiased ring drawing — allocated once,
    // mutated per ring inside the Canvas lambda (safe: Canvas lambdas are
    // single-threaded on the UI thread).
    val ringPaint = remember { Paint().apply { isAntiAlias = true; style = PaintingStyle.Stroke } }

    val transition = rememberInfiniteTransition(label = "connecting_bg")

    // Linear driver for the rings — each ring offset by 1/RING_COUNT of the period
    val ringPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RING_CYCLE_MS, easing = LinearEasing),
        ),
        label = "ring_phase",
    )

    // Shared particle twinkle driver — each particle shifts its own phase offset
    val particlePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
        ),
        label = "particle_phase",
    )

    // Slow breathing glow at the centre — eases in/out for organic feel
    val glowPulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_pulse",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = sqrt(cx * cx + cy * cy)

        // ── Layer 1: background gradient ──────────────────────────────────────
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFF0E1724),
                    0.50f to Color(0xFF090D15),
                    1.00f to Color(0xFF040608),
                ),
                center = Offset(cx, cy),
                radius = maxR,
            ),
        )

        // ── Layer 2: particles ────────────────────────────────────────────────
        // Each particle runs a triangle wave (0→1→0) shifted by its own phase,
        // so they twinkle independently while sharing one animated driver value.
        particles.forEach { p ->
            val t = (particlePhase + p.phase) % 1f
            val wave = if (t < 0.5f) t * 2f else (1f - t) * 2f   // 0..1..0
            val alpha = wave * 0.45f
            drawCircle(
                color = Color(0xFF7DD3FC).copy(alpha = alpha),
                radius = p.radiusPx,
                center = Offset(size.width * p.xFrac, size.height * p.yFrac),
            )
        }

        // ── Layer 3: concentric rings (antialiased) ───────────────────────────
        // Rings are evenly staggered: ring i starts at phase offset i/RING_COUNT.
        // Alpha uses a power curve so rings are nearly invisible when tiny,
        // most vivid at ~30 % radius, then fade to nothing at the edge.
        // drawIntoCanvas + Paint(isAntiAlias=true) ensures Skia renders each ring
        // with sub-pixel smoothing so edges never appear jagged.
        drawIntoCanvas { canvas ->
            repeat(RING_COUNT) { i ->
                val phase = (ringPhase + i.toFloat() / RING_COUNT) % 1f
                val radius = phase * maxR * 0.80f
                val alpha = (1f - phase).pow(1.6f) * 0.60f
                val strokePx = (1f - phase) * 4f + 1f

                ringPaint.color = Color(0xFF00C8E8).copy(alpha = alpha.coerceIn(0f, 1f))
                ringPaint.strokeWidth = strokePx

                canvas.drawCircle(
                    center = Offset(cx, cy),
                    radius = radius.coerceAtLeast(1f),
                    paint = ringPaint,
                )
            }
        }

        // ── Layer 4: breathing centre glow ────────────────────────────────────
        val glowR = maxR * (0.20f + glowPulse * 0.08f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFF00C8E8).copy(alpha = 0.20f + glowPulse * 0.14f),
                    0.45f to Color(0xFF0066BB).copy(alpha = 0.07f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = glowR,
            ),
            radius = glowR,
            center = Offset(cx, cy),
        )
    }
}
