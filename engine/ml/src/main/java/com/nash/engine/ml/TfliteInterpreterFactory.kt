package com.nash.engine.ml

import android.content.Context
import com.nash.core.model.DetectorDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate

/**
 * A live LiteRT (TFLite) [Interpreter] together with the delegate it was
 * created with.
 *
 * Owning both in one holder is the point: a delegate must outlive the
 * interpreter that uses it and must be closed *after* it, which is easy to get
 * wrong when the two are tracked in separate fields on a detector.
 */
internal class TfliteRuntime(
    val interpreter: Interpreter,
    private val delegate: NnApiDelegate?
) {

    /** True when inference runs on the NNAPI delegate, false on CPU/XNNPACK. */
    val isAccelerated: Boolean get() = delegate != null

    /** Closes the interpreter first, then the delegate it borrowed. */
    fun close() {
        interpreter.close()
        delegate?.close()
    }
}

/**
 * Builds [TfliteRuntime]s: model asset loading, delegate policy and the
 * NNAPI -> CPU fallback live here, not in the detectors.
 *
 * Policy (unchanged from the previous inline implementation):
 * - [DetectorDelegate.NPU] tries an [NnApiDelegate] with fp16 relaxation,
 *   sustained-speed execution preference and a compilation cache keyed by a
 *   caller-supplied model token.
 * - Any failure while creating the delegate or the delegated interpreter falls
 *   through to a CPU/XNNPACK interpreter instead of crashing the pipeline.
 * - Every other delegate preference (CPU, GPU) uses the CPU interpreter today;
 *   GPU stays reserved for the render path.
 *
 * Model loading is blocking I/O: call [create] from the ml dispatcher (the
 * detectors do this by keeping their runtime in a `lazy`), never from DI
 * construction.
 */
internal class TfliteInterpreterFactory(private val context: Context) {

    /**
     * @param modelAsset asset path of the `.tflite` model.
     * @param delegate preferred accelerator; falls back to CPU when unavailable.
     * @param modelToken compilation-cache token. Bump it whenever the model
     * file changes, otherwise the driver may reuse a stale compiled artifact.
     * @param cpuThreads thread count for the CPU fallback interpreter.
     */
    fun create(
        modelAsset: String,
        delegate: DetectorDelegate,
        modelToken: String,
        cpuThreads: Int = CPU_NUM_THREADS
    ): TfliteRuntime {
        val model = loadModel(modelAsset)

        if (delegate == DetectorDelegate.NPU) {
            createNnApiRuntime(model, modelToken)?.let { return it }
        }

        // The delegated attempt may have consumed part of the buffer before
        // failing; rewind so the CPU interpreter always sees the whole model.
        model.rewind()
        return TfliteRuntime(
            interpreter = Interpreter(
                model,
                Interpreter.Options().setNumThreads(cpuThreads)
            ),
            delegate = null
        )
    }

    /** Reads the model asset into a direct, native-order buffer. */
    private fun loadModel(modelAsset: String): ByteBuffer {
        val bytes = context.assets.open(modelAsset).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        return buffer
    }

    /** Returns null when NNAPI is unusable, signalling "fall through to CPU". */
    private fun createNnApiRuntime(model: ByteBuffer, modelToken: String): TfliteRuntime? {
        var delegate: NnApiDelegate? = null
        return try {
            delegate = NnApiDelegate(
                NnApiDelegate.Options()
                    .setAllowFp16(true)
                    .setExecutionPreference(
                        NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
                    )
                    // Compilation cache: the first-run driver compile is slow.
                    .setCacheDir(context.cacheDir.absolutePath)
                    .setModelToken(modelToken)
            )
            val interpreter = Interpreter(
                model,
                Interpreter.Options().addDelegate(delegate)
            )
            TfliteRuntime(interpreter, delegate)
        } catch (_: Exception) {
            // NNAPI unavailable, or the vendor driver rejects the model:
            // release whatever was created and let the caller use CPU.
            delegate?.close()
            null
        }
    }

    private companion object {
        const val CPU_NUM_THREADS = 2
    }
}