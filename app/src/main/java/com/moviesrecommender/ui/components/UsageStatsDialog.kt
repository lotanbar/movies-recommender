package com.moviesrecommender.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.moviesrecommender.data.remote.anthropic.UsageStats

private val DIALOG_CORNER_SHAPE = RoundedCornerShape(4.dp)

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
fun UsageStatsDialog(stats: UsageStats, onDismiss: () -> Unit) {
    val rows = listOf(
        "Duration" to formatDuration(stats.durationMs),
        "Cost" to "$${"%.4f".format(stats.costUsd)}"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DIALOG_CORNER_SHAPE,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.2f,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = value,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.2f,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
