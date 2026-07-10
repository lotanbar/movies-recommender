package com.moviesrecommender.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * A pulsing alpha, ~0.15 to ~0.55, looping while [isActive] is true and 0 otherwise. Tint a
 * row/thumbnail's background with it (in the app's primary color) for a "flashing" busy state —
 * there is no other shimmer/pulse utility in the app to reuse.
 */
@Composable
fun rememberAssessPulseAlpha(isActive: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "assessPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "assessPulseAlpha"
    )
    return if (isActive) pulse else 0f
}
