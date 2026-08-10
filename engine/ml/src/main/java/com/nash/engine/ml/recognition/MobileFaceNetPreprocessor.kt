package com.nash.engine.ml.recognition

/**
 * Pixel -> input-tensor conversion for MobileFaceNet.
 *
 * Extracted from the recognizer so it can be tested on the JVM, and so the
 * fill runs against a plain FloatArray. Writing 112k values through
 * ByteBuffer.putFloat measured 54 ms per pass; array stores plus one bulk
 * copy is the same arithmetic without the per-element buffer overhead.
 *
 * Layout is NHWC RGB with the MobileFaceNet convention (x - 127.5) / 127.5.
 * The alpha channel is ignored.
 */
internal object MobileFaceNetPreprocessor {

    /** RGB. */
    const val CHANNELS = 3

    private const val MEAN = 127.5f

    /** Reciprocal, so the hot loop multiplies instead of dividing. */
    private const val INV_STD = 1f / 127.5f

    /**
     * Fills [out] from ARGB_8888 [pixels] in row-major RGB order.
     *
     * @param pixels source pixels, as returned by Bitmap.getPixels.
     * @param out destination, exactly `pixels.size * CHANNELS` long; fully
     *   overwritten, so it is safe to reuse across passes.
     */
    fun fill(pixels: IntArray, out: FloatArray) {
        require(out.size == pixels.size * CHANNELS) {
            "out must be ${pixels.size * CHANNELS} floats, was ${out.size}"
        }
        var i = 0
        for (pixel in pixels) {
            out[i++] = (((pixel shr 16) and 0xFF) - MEAN) * INV_STD
            out[i++] = (((pixel shr 8) and 0xFF) - MEAN) * INV_STD
            out[i++] = ((pixel and 0xFF) - MEAN) * INV_STD
        }
    }
}
