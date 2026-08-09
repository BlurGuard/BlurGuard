package com.nash.engine.impl.factory

import com.nash.core.model.OcSortConfig
import com.nash.core.model.TrackerBackend
import com.nash.core.model.TrackerConfig
import com.nash.engine.tracking.ByteTrackTracker
import com.nash.engine.tracking.ocsort.OcSortTracker
import javax.inject.Provider
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTrackerFactoryTest {

    private val byteTrack = ByteTrackTracker(TrackerConfig())
    private val ocSort = OcSortTracker(OcSortConfig())
    private val factory = DefaultTrackerFactory(
        byteTrack = Provider { byteTrack },
        ocSort = Provider { ocSort },
    )

    @Test
    fun `BYTE_TRACK backend returns the byte track tracker`() {
        val tracker = factory.create(TrackerConfig(backend = TrackerBackend.BYTE_TRACK))
        assertSame(byteTrack, tracker)
        assertTrue(tracker is ByteTrackTracker)
    }

    @Test
    fun `OC_SORT backend returns the oc sort tracker`() {
        val tracker = factory.create(TrackerConfig(backend = TrackerBackend.OC_SORT))
        assertSame(ocSort, tracker)
        assertTrue(tracker is OcSortTracker)
    }

    @Test
    fun `default config selects BYTE_TRACK`() {
        assertSame(byteTrack, factory.create(TrackerConfig()))
    }
}
