package com.nash.engine.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.FrameConsumer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.FrameSource
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the [ImageAnalysis] analyzer: converts [ImageProxy] metadata to
 * [FrameMetadata], generates frame IDs, and delivers frames to a single
 * [FrameConsumer] on the serialized ml dispatcher.
 *
 * Frame ownership stays here — the [ImageProxy] is always closed in a
 * `finally` block after the consumer returns, which is also what drives
 * CameraX's latest-wins backpressure (STRATEGY_KEEP_ONLY_LATEST).
 */
@Singleton
class AnalysisFrameSource @Inject constructor(
    dispatcherProvider: DispatcherProvider,
) : FrameSource<ImageProxy> {

    /**
     * Scope for per-frame analysis work. The ml dispatcher has parallelism 1,
     * so frames are processed strictly one at a time, in order, without
     * blocking a thread.
     */
    private val analysisScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.ml
    )

    private val frameIdGenerator = AtomicLong(0L)

    @Volatile
    private var frameConsumer: FrameConsumer<ImageProxy>? = null

    override fun setFrameConsumer(consumer: FrameConsumer<ImageProxy>?) {
        frameConsumer = consumer
    }

    /**
     * Attaches the analyzer to [imageAnalysis].
     *
     * Runnable::run is a direct executor: the callback only builds metadata
     * and hands the frame to the analysis coroutine, so it is cheap enough
     * to run on CameraX's own thread.
     */
    fun attachTo(imageAnalysis: ImageAnalysis) {
        imageAnalysis.setAnalyzer(Runnable::run) { imageProxy ->
            val consumer = frameConsumer
            if (consumer == null) {
                imageProxy.close()
                return@setAnalyzer
            }
            val metadata = buildMetadata(imageProxy)
            analysisScope.launch {
                try {
                    consumer.onFrame(imageProxy, metadata)
                } finally {
                    // Closing the frame is what lets CameraX deliver the next
                    // (latest) one — this IS the backpressure/subsampling signal.
                    imageProxy.close()
                }
            }
        }
    }

    fun detachFrom(imageAnalysis: ImageAnalysis) {
        imageAnalysis.clearAnalyzer()
    }

    private fun buildMetadata(imageProxy: ImageProxy): FrameMetadata {
        val crop = imageProxy.cropRect
        return FrameMetadata(
            frameId = frameIdGenerator.incrementAndGet(),
            timestampNanos = imageProxy.imageInfo.timestamp,
            width = imageProxy.width,
            height = imageProxy.height,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            cropLeft = crop.left,
            cropTop = crop.top,
            cropWidth = crop.width(),
            cropHeight = crop.height(),
        )
    }

    fun shutdown() {
        analysisScope.cancel()
    }
}