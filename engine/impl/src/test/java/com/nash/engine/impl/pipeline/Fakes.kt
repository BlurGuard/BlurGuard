package com.nash.engine.impl.pipeline

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.Detector
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.PersonId
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import com.nash.core.model.VerificationState
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Frames are just strings in tests — the pipeline never inspects them. */
internal typealias TestFrame = String

internal fun meta(
    frameId: Long,
    width: Int = 640,
    height: Int = 480,
    rotationDegrees: Int = 0,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropWidth: Int = 0,
    cropHeight: Int = 0,
) = FrameMetadata(
    frameId = frameId,
    timestampNanos = frameId * 1_000_000L,
    width = width,
    height = height,
    rotationDegrees = rotationDegrees,
    cropLeft = cropLeft,
    cropTop = cropTop,
    cropWidth = cropWidth,
    cropHeight = cropHeight,
)

internal fun detection(
    left: Float = 0f, top: Float = 0f, right: Float = 1f, bottom: Float = 1f,
    clazz: DetectionClass = DetectionClass.FACE,
    confidence: Float = 0.9f,
) = DetectionBox(BoundingBox(left, top, right, bottom), clazz, confidence)

internal fun tracked(
    id: Long = 1L,
    left: Float = 0f, top: Float = 0f, right: Float = 1f, bottom: Float = 1f,
    clazz: DetectionClass = DetectionClass.FACE,
    confidence: Float = 0.9f,
    lastUpdatedFrame: Long = 0L,
    keepVisible: Boolean = false,
) = TrackedBox(
    id = TrackId(id),
    box = BoundingBox(left, top, right, bottom),
    clazz = clazz,
    confidence = confidence,
    lastUpdatedFrame = lastUpdatedFrame,
    keepVisible = keepVisible,
)

/** Detector that returns a fixed list, or throws a fixed error. */
internal class FakeDetector(
    private val result: List<DetectionBox> = emptyList(),
    private val error: Throwable? = null,
) : Detector<TestFrame> {
    var detectCalls = 0
        private set
    var closed = false
        private set

    override suspend fun detect(frame: TestFrame, metadata: FrameMetadata): List<DetectionBox> {
        detectCalls++
        error?.let { throw it }
        return result
    }

    override fun close() { closed = true }
}

/** Records update/predict/reset traffic and replays canned boxes. */
internal class FakeTracker(
    private val onUpdate: List<TrackedBox> = emptyList(),
    private val onPredict: List<TrackedBox> = emptyList(),
) : Tracker {
    val updatedFrameIds = mutableListOf<Long>()
    val predictedFrameIds = mutableListOf<Long>()
    var resetCount = 0
        private set
    var lastDetections: List<DetectionBox> = emptyList()
        private set

    override fun update(
        detections: List<DetectionBox>,
        metadata: FrameMetadata
    ): List<TrackedBox> {
        updatedFrameIds += metadata.frameId
        lastDetections = detections
        return onUpdate
    }

    override fun predict(metadata: FrameMetadata): List<TrackedBox> {
        predictedFrameIds += metadata.frameId
        return onPredict
    }

    override fun reset() { resetCount++ }
}

/** Recognition seam stub — no TFLite, no trusted-person store. */
internal class FakeKeepVisibleRecognizer : KeepVisibleRecognizer<TestFrame> {
    val detectionFrameIds = mutableListOf<Long>()
    var sessionResets = 0
        private set

    override suspend fun onDetectionFrame(
        frame: TestFrame,
        metadata: FrameMetadata,
        boxes: List<TrackedBox>
    ) {
        detectionFrameIds += metadata.frameId
    }

    override fun onSessionReset() { sessionResets++ }
}

/**
 * Verification-state stub. Keeps engine/impl's tests independent of
 * engine/recognition's real store: the pipeline only needs *some* state to
 * read, and these tests are about cadence and publishing, not trust policy.
 *
 * Records retain/clear traffic so pipeline tests can assert on it, and exposes
 * [trust] so the render gate can be driven directly.
 */
internal class FakeKeepVisibleState : KeepVisibleStateStore {

    private val _verifications = MutableStateFlow<Map<TrackId, TrackVerification>>(emptyMap())
    override val verifications: StateFlow<Map<TrackId, TrackVerification>> =
        _verifications.asStateFlow()

    val retainedTrackIds = mutableListOf<Set<TrackId>>()
    var clearAllCount = 0
        private set

    override fun of(trackId: TrackId): TrackVerification =
        _verifications.value[trackId] ?: TrackVerification()

    override fun set(trackId: TrackId, verification: TrackVerification) {
        _verifications.value = _verifications.value + (trackId to verification)
    }

    override fun retainTracks(liveTrackIds: Set<TrackId>) {
        retainedTrackIds += liveTrackIds
        _verifications.value = _verifications.value.filterKeys { it in liveTrackIds }
    }

    override fun clearAll() {
        clearAllCount++
        _verifications.value = emptyMap()
    }

    /** Marks a track TRUSTED, i.e. the one state the render gate unblurs. */
    fun trust(trackId: TrackId) = set(
        trackId,
        TrackVerification(state = VerificationState.TRUSTED, personId = PersonId(1L))
    )
}

/** Captures detector failures instead of writing to logcat. */
internal class RecordingFailurePolicy : DetectorFailurePolicy {
    val failures = mutableListOf<Throwable>()
    override fun onFailure(detector: Detector<*>, error: Throwable) {
        failures += error
    }
}

/** Monotonic fake clock, advanced explicitly by tests. */
internal class FakeClock(private var now: Long = 0L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(nanos: Long) { now += nanos }
}