package com.nash.engine.impl.factory

import com.nash.core.model.Tracker
import com.nash.core.model.TrackerBackend
import com.nash.core.model.TrackerConfig
import com.nash.engine.tracking.ByteTrackTracker
import com.nash.engine.tracking.ocsort.OcSortTracker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Selects the live [Tracker] from [TrackerConfig.backend].
 *
 * Replaces EngineImplModule's hardcoded `provideTracker`, which always
 * returned ByteTrack and left OC-SORT unwired. [Provider]s keep the old lazy
 * semantics: only the selected backend is ever instantiated.
 */
@Singleton
internal class DefaultTrackerFactory @Inject constructor(
    private val byteTrack: Provider<ByteTrackTracker>,
    private val ocSort: Provider<OcSortTracker>,
) : TrackerFactory {

    override fun create(config: TrackerConfig): Tracker = when (config.backend) {
        TrackerBackend.BYTE_TRACK -> byteTrack.get()
        TrackerBackend.OC_SORT -> ocSort.get()
    }
}
