package com.nash.feature.camera.components

import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.VerificationState
import com.nash.feature.camera.R

/**
 * "Re-blur all" affordance, shown only while at least one track is
 * PENDING or TRUSTED.
 */
@Composable
fun KeepVisibleControls(
    keepVisible: Map<TrackId, TrackVerification>,
    onRevokeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val anyKeptVisible = keepVisible.values.any {
        it.state == VerificationState.TRUSTED || it.state == VerificationState.PENDING
    }
    if (anyKeptVisible) {
        AssistChip(
            onClick = onRevokeAll,
            label = { Text(stringResource(R.string.keep_visible_revoke_all)) },
            modifier = modifier
        )
    }
}

@Preview
@Composable
private fun KeepVisibleControlsPreview() {
    KeepVisibleControls(
        keepVisible = mapOf(
            TrackId(1L) to TrackVerification(state = VerificationState.TRUSTED)
        ),
        onRevokeAll = {}
    )
}