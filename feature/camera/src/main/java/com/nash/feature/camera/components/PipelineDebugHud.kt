package com.nash.feature.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nash.core.model.PipelineStats
import com.nash.feature.camera.state.CameraDebugStatsUiModel

/**
 * Debug-only pipeline HUD: frame/detection rates, latency, and tracked-ID
 * counters. Both lines share the same styling (fixes the previously
 * unstyled "ids: …" text).
 */
@Composable
fun PipelineDebugHud(
    stats: PipelineStats,
    debugStats: CameraDebugStatsUiModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DebugHudText(
            text = "%.0f fps · det %.1f/s · %d ms".format(
                stats.frameFps, stats.fps, stats.detectionLatencyMillis
            )
        )
        DebugHudText(
            text = "ids: ${debugStats.active} active / ${debugStats.totalSeen} seen"
        )
    }
}

@Composable
private fun DebugHudText(text: String) {
    Text(
        text = text,
        color = Color.Green,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Preview
@Composable
private fun PipelineDebugHudPreview() {
    PipelineDebugHud(
        stats = PipelineStats(frameFps = 30f, fps = 7.5f, detectionLatencyMillis = 42L),
        debugStats = CameraDebugStatsUiModel(active = 3, totalSeen = 17)
    )
}
