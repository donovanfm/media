/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.effect.sticker

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Lifecycle-safe wrapper around MediaPipe's [InteractiveSegmenter] (tasks-vision 1.0 API).
 *
 * All native calls — creation, every [segment], and [close] — are confined to one single-thread
 * executor, so closing the engine can never interleave with an in-flight native segmentation (the
 * close task queues behind it). Create the engine once per screen, call [prepare] before use, and
 * [close] it deterministically; the segmenter holds a native interpreter plus the 6 MB model.
 *
 * [prepare] walks a delegate preference chain (NPU, GPU, CPU), and inference failures advance the
 * chain too, since hardware delegates can initialize on devices that can't actually run the model
 * (emulators, notably).
 */
internal class SegmenterEngine(
  private val context: Context,
  private val modelAssetPath: String = MODEL_ASSET_PATH,
) {

  /** A confidence mask: float values in [0, 1], row-major at [width] x [height]. */
  class ConfidenceMask(val values: FloatBuffer, val width: Int, val height: Int)

  /** Wraps MediaPipe failures so callers can handle them without depending on MediaPipe types. */
  class SegmentationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

  private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "SegmenterEngine") }
  private val dispatcher = executor.asCoroutineDispatcher()

  // Confined to the engine thread.
  private var segmenter: InteractiveSegmenter? = null
  private var delegateIndex = 0

  @Volatile private var closed = false

  /** The delegate currently running inference, or null before [prepare]. */
  @Volatile
  var activeDelegate: Delegate? = null
    private set

  /**
   * Creates the segmenter if it doesn't exist yet, trying delegates in [DELEGATE_PREFERENCE]
   * order: NPU first (the int8 model bundle is exactly what NPUs run best), then GPU, then CPU.
   * Idempotent. Throws [SegmentationException] when no delegate works.
   */
  suspend fun prepare() {
    withContext(dispatcher) {
      if (closed || segmenter != null) {
        return@withContext
      }
      createFromChain(startIndex = 0)
    }
  }

  /**
   * Creates the segmenter with the first working delegate at or after [startIndex] in
   * [DELEGATE_PREFERENCE], updating [segmenter]/[activeDelegate]. Engine thread only. Throws
   * [SegmentationException] when the rest of the chain is exhausted.
   */
  private fun createFromChain(startIndex: Int): InteractiveSegmenter {
    var lastFailure: RuntimeException? = null
    for (i in startIndex until DELEGATE_PREFERENCE.size) {
      val delegate = DELEGATE_PREFERENCE[i]
      try {
        val created = createSegmenter(delegate)
        segmenter = created
        delegateIndex = i
        activeDelegate = delegate
        Log.i(TAG, "InteractiveSegmenter ready with the $delegate delegate")
        return created
      } catch (e: RuntimeException) {
        Log.i(TAG, "$delegate delegate unavailable", e)
        lastFailure = e
      }
    }
    Log.e(TAG, "Failed to initialize InteractiveSegmenter", lastFailure)
    throw SegmentationException("Failed to initialize InteractiveSegmenter", lastFailure)
  }

  /**
   * Segments the object in [frame] at the normalized point ([x], [y]) and returns its confidence
   * mask. The point is submitted as a single-point positive brush stroke.
   *
   * Hardware inference is only initialized by the graph on the first segmentation — creating the
   * segmenter with an NPU or GPU delegate can succeed on devices that can't actually run the
   * model on that hardware (emulators, notably). A failure therefore advances to the next
   * delegate in the chain and retries.
   *
   * Serialized on the engine thread, but the suspension itself is cancellable: wrapping a call in
   * `withTimeout` abandons the wait (the native call keeps running on the engine thread and its
   * eventual result is discarded), so a wedged native call can't hang the caller.
   *
   * Throws [SegmentationException] on MediaPipe errors and [CancellationException] when the
   * engine is closed or unprepared.
   */
  suspend fun segment(frame: Bitmap, x: Float, y: Float): ConfidenceMask {
    return suspendCancellableCoroutine { continuation ->
      executor.execute {
        val initialSegmenter = this.segmenter
        if (closed || initialSegmenter == null) {
          continuation.cancel(CancellationException("SegmenterEngine is closed"))
          return@execute
        }
        var segmenter: InteractiveSegmenter = initialSegmenter
        while (true) {
          try {
            val startTimeMs = SystemClock.elapsedRealtime()
            val mask = runSegmentation(segmenter, frame, x, y)
            Log.d(
              TAG,
              "Segmented ${frame.width}x${frame.height} in " +
                "${SystemClock.elapsedRealtime() - startTimeMs}ms on $activeDelegate",
            )
            continuation.resume(mask)
            return@execute
          } catch (e: RuntimeException) {
            if (delegateIndex >= DELEGATE_PREFERENCE.size - 1) {
              Log.e(TAG, "Segmentation failed", e)
              continuation.resumeWithException(SegmentationException("Segmentation failed", e))
              return@execute
            }
            Log.i(TAG, "Inference failed on $activeDelegate, advancing delegate chain", e)
            try {
              segmenter.close()
              segmenter = createFromChain(delegateIndex + 1)
            } catch (e2: SegmentationException) {
              continuation.resumeWithException(e2)
              return@execute
            }
          }
        }
      }
    }
  }

  private fun runSegmentation(
    segmenter: InteractiveSegmenter,
    frame: Bitmap,
    x: Float,
    y: Float,
  ): ConfidenceMask {
    // Don't close the input image: closing a bitmap-backed MPImage recycles the bitmap, which the
    // caller reuses for the next capture.
    segmenter.setImage(BitmapImageBuilder(frame).build())
    val mask =
      segmenter.segment(
        listOf(
          Stroke.builder()
            .setBrushMode(Stroke.BrushMode.POSITIVE)
            .setPoints(listOf(NormalizedKeypoint.create(x, y)))
            .setCompleted(true)
            .build()
        )
      )
    // The mask is backed by a fixed-size native buffer pool. It MUST be closed after its data is
    // copied out, or the pool runs dry and a later segment() call blocks forever inside native
    // code (in practice: recording froze after ~3 frames).
    return try {
      toConfidenceMask(mask)
    } finally {
      mask.close()
    }
  }

  /**
   * Closes the engine. Safe to call with a [segment] in flight: the close task queues on the same
   * single thread, so it runs strictly after any in-flight native call. Idempotent.
   */
  fun close() {
    if (closed) {
      return
    }
    closed = true
    executor.execute {
      segmenter?.close()
      segmenter = null
    }
    executor.shutdown()
  }

  private fun createSegmenter(delegate: Delegate): InteractiveSegmenter {
    val options =
      InteractiveSegmenterOptions.builder()
        .setBaseOptions(
          BaseOptions.builder().setModelAssetPath(modelAssetPath).setDelegate(delegate).build()
        )
        .build()
    return InteractiveSegmenter.createFromOptions(context, options)
  }

  /**
   * Normalizes the returned mask image to floats in [0, 1], accepting either a float
   * (VEC32F1-style) or 8-bit alpha buffer. Always copies to the heap: the source buffer belongs
   * to the mask image, which the caller closes immediately afterwards.
   */
  private fun toConfidenceMask(mask: MPImage): ConfidenceMask {
    val pixelCount = mask.width * mask.height
    val buffer = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder())
    val floats = FloatBuffer.allocate(pixelCount)
    when (buffer.capacity()) {
      pixelCount * Float.SIZE_BYTES -> floats.put(buffer.asFloatBuffer())
      pixelCount -> {
        for (i in 0 until pixelCount) {
          floats.put((buffer.get(i).toInt() and 0xFF) / 255f)
        }
      }
      else ->
        throw SegmentationException(
          "Unexpected mask buffer: ${buffer.capacity()} bytes for $pixelCount pixels"
        )
    }
    floats.rewind()
    return ConfidenceMask(floats, mask.width, mask.height)
  }

  companion object {
    private const val TAG = "SegmenterEngine"
    // Ordering chosen from on-device measurements (Pixel 9 Pro, 512x288 frames): CPU (XNNPACK)
    // ~800ms with good masks; NPU identical to CPU (NNAPI is deprecated and resolves to a CPU
    // path on recent devices); GPU ~1700ms AND produced corrupt masks for this int8 bundle. CPU
    // is therefore the deterministic default, GPU strictly a last resort.
    private val DELEGATE_PREFERENCE = listOf(Delegate.CPU, Delegate.NPU, Delegate.GPU)
    // The v2 task bundle required by the tasks-vision 1.0 InteractiveSegmenter (the legacy
    // magic_touch.tflite only works with InteractiveSegmenterLegacy). Downloaded at build time;
    // see downloadSegmenterModel in build.gradle.kts.
    // Internal so the effect screen can check the asset's presence: builds made offline (or with
    // -PskipStickerModelDownload) don't bundle the model, and creation is disabled gracefully.
    internal const val MODEL_ASSET_PATH = "interactive_segmentation.task"
  }
}
