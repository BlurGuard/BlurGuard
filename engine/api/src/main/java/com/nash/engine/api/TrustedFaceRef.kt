package com.nash.engine.api

import com.nash.core.model.TrackId

/**
 * A safe reference to a face that should remain visible (not anonymized).
 */
data class TrustedFaceRef(
    val trackId: TrackId
)
