package com.nash.feature.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nash.engine.api.AnonymizationMode

/** Tappable chip showing the active anonymization mode; cycles on click. */
@Composable
fun ModeChip(
    mode: AnonymizationMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = mode.name,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Preview
@Composable
private fun ModeChipPreview() {
    ModeChip(mode = AnonymizationMode.BLUR, onClick = {})
}