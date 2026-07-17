package com.moviesrecommender.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Ticks once a second while [isRunning] is true, resetting to 0 each time it starts. There's no
 * server-pushed progress for a single Claude call, so this is a plain wall-clock count — a UI
 * affordance for "still working," not a measurement tied to any request/response event.
 */
@Composable
fun rememberElapsedSeconds(isRunning: Boolean): Int {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            seconds = 0
            while (isActive) {
                delay(1000)
                seconds++
            }
        }
    }
    return seconds
}

/** "m:ss", matching the format already used for historical durations in UsageScreen. */
fun formatElapsed(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
