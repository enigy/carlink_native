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
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

// Scattered background particles that twinkle independently for a subtle sense of depth.
private const val PARTICLE_COUNT = 48

private data class Particle(
    val xFrac: Float,   // 0..1 fraction of canvas width
    val yFrac: Float,   // 0..1 fraction of canvas height
    val phase: Float,   // 0..1 offset so particles twinkle out of sync
    val radiusPx: Float,
)

/**
 * Transparent overlay of softly twinkling sparkle particles for the connecting phase.
 *
 * Draws ONLY the particles on a transparent canvas. The static loading background image
 * ([com.carlink.R.drawable.loading_bg]) is composed BEHIND this in MainScreen, and the icon +
 * status text sit on top. The earlier concentric rings, dark radial gradient, and breathing
 * centre glow were removed when the mountain background replaced them — only the sparkles remain.
 *
 * Particles are white (read well over the warm image) and ramp alpha via a triangle wave driven by
 * a single [rememberInfiniteTransition], so no per-particle coroutines are needed.
 */
@Composable
fun AnimatedConnectingBackground(modifier: Modifier = Modifier) {
    // Fixed random particles — regenerated only if the composable is fully disposed.
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

    val transition = rememberInfiniteTransition(label = "connecting_bg")
    val particlePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
        ),
        label = "particle_phase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        // Each particle runs a triangle wave (0→1→0) shifted by its own phase, so they twinkle
        // independently while sharing one animated driver value.
        particles.forEach { p ->
            val t = (particlePhase + p.phase) % 1f
            val wave = if (t < 0.5f) t * 2f else (1f - t) * 2f // 0..1..0
            val alpha = wave * 0.55f
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = p.radiusPx,
                center = Offset(size.width * p.xFrac, size.height * p.yFrac),
            )
        }
    }
}
