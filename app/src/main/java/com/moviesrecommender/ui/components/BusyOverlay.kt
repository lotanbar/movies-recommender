package com.moviesrecommender.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.moviesrecommender.util.ToastManager

/**
 * Full-size transparent overlay that swallows all taps/drags while [isBusy] is true, showing
 * [message] as a toast on tap. Render it as the last child of the screen's outermost Box so it
 * sits above every control on the screen without needing to thread `enabled=` through each one.
 */
@Composable
fun BusyOverlay(isBusy: Boolean, message: String, modifier: Modifier = Modifier) {
    if (!isBusy) return
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { ToastManager.show(message) })
            }
    )
}
