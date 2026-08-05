package com.nash.engine.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.round

/**
 * Turns a camera [ImageProxy] into the float32 RGB tensor the YOLO model
 * expects: letterboxed to `inputSize` x `inputSize` with gray padding
 * (Ultralytics convention), normalized to 0..1, written in NCHW or NHWC
 * depending on [channelsFirst].
 *
 * ## Ownership of the input buffer
 * [process] fills [inputBuffer] in place and returns only the letterbox
 * metadata the decoder needs to map results back to buffer space. The buffer
 * is owned and reused by this class; the caller must hand it to
 * `Interpreter.run` before the next [process] call and must not retain it.
 *
 * ## Thread-safety invariant — read this before reusing the class
 * Every scratch object here (input buffer, pixel array, letterbox bitmap, its
 * canvas, the destination rect) is reused across frames. That is only sound
 * because detection runs on the *serialized* ml dispatcher: exactly one frame
 * is in flight at a time. **This class is deliberately not thread-safe and
 * must not be made thread-safe** — one instance per detector, touched only
 * from the ml dispatcher. Sharing an instance across dispatchers would corrupt
 * a frame mid-flight, silently and non-deterministically.
 *
 * @param inputSize square model input edge, in pixels.
 * @param channelsFirst true for NCHW models, false for NHWC. The tensor-shape
 * inspection that determines this stays with the interpreter owner; this class
 * is told the answer.
 */
internal class YoloPreprocessor(
    private val inputSize: Int,
    private val channelsFirst: Boolean
) {

    /** Filled by [process]; reused across frames. Never retain or mutate externally. */
    val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())

    // --- Reused scratch state. See the thread-safety invariant above.
    private val pixels = IntArray(inputSize * inputSize)
    private val letterboxed: Bitmap = createBitmap(inputSize, inputSize)
    private val canvas = Canvas(letterboxed)
    private val destination = Rect()

    /**
     * Letterboxes [frame] into [inputBuffer].
     *
     * @return how the source buffer was fitted into the model input, to be
     * passed verbatim to [YoloOutputDecoder.decode].
     */
    fun process(frame: ImageProxy): YoloOutputDecoder.Letterbox {
        // CameraX allocates a fresh Bitmap per call here, and that is left
        // alone on purpose: ImageProxy.toBitmap() does the YUV_420_888 -> RGB
        // conversion internally and exposes no reusable output bitmap. Feeding
        // it a recycled bitmap is not part of the API, and hand-rolling the
        // YUV conversion to save one allocation would trade a well-tested
        // colour path for a subtle correctness risk on the privacy-critical
        // detection path. Kept explicit rather than "optimized" blindly.
        val source = frame.toBitmap()

        val fit = fit(source.width, source.height, inputSize)

        // --- Letterbox into the reused bitmap; the gray fill also wipes the
        // previous frame, so no stale pixels can survive in the padding.
        canvas.drawColor(Color.rgb(114, 114, 114))
        destination.set(
            fit.padX.toInt(),
            fit.padY.toInt(),
            fit.padX.toInt() + fit.scaledWidth,
            fit.padY.toInt() + fit.scaledHeight
        )
        canvas.drawBitmap(source, null, destination, null)

        // --- Bitmap -> float32 RGB buffer, 0..1.
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        writeRgbFloats(pixels, inputBuffer, channelsFirst)

        return YoloOutputDecoder.Letterbox(
            scale = fit.scale,
            padX = fit.padX,
            padY = fit.padY,
            bufferWidth = source.width,
            bufferHeight = source.height
        )
    }

    /** Pure letterbox geometry, extracted so it can be unit-tested off-device. */
    internal data class Fit(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val scaledWidth: Int,
        val scaledHeight: Int
    )

    internal companion object {

        /**
         * Scale-to-fit with centered padding: the source keeps its aspect
         * ratio and is centered in a square [inputSize] canvas.
         */
        internal fun fit(sourceWidth: Int, sourceHeight: Int, inputSize: Int): Fit {
            val scale = min(
                inputSize / sourceWidth.toFloat(),
                inputSize / sourceHeight.toFloat()
            )
            val scaledWidth = round(sourceWidth * scale).toInt()
            val scaledHeight = round(sourceHeight * scale).toInt()
            return Fit(
                scale = scale,
                padX = (inputSize - scaledWidth) / 2f,
                padY = (inputSize - scaledHeight) / 2f,
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight
            )
        }

        /**
         * Writes ARGB_8888 [pixels] into [buffer] as 0..1 float32 RGB.
         * Pure JVM (no Android types), so the layout is unit-testable without
         * Robolectric. The buffer is rewound before and after writing.
         */
        internal fun writeRgbFloats(
            pixels: IntArray,
            buffer: ByteBuffer,
            channelsFirst: Boolean
        ) {
            buffer.rewind()
            if (channelsFirst) {
                // NCHW: full R plane, then G, then B.
                for (pixel in pixels) buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                for (pixel in pixels) buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                for (pixel in pixels) buffer.putFloat((pixel and 0xFF) / 255f)
            } else {
                // NHWC: interleaved RGB per pixel.
                for (pixel in pixels) {
                    buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                    buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                    buffer.putFloat((pixel and 0xFF) / 255f)
                }
            }
            buffer.rewind()
        }
    }
}