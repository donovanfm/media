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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Lifecycle-safe wrapper around MediaPipe's [InteractiveSegmenter] (tasks-vision 1.0 API).
 *
 * All native calls — creation, every [segment], and [close] — are confined to one single-thread
 * executor, so closing the engine can never interleave with an in-flight native segmentation (the
 * close task queues behind it). Create the engine once per screen, call [prepare] before use, and
 * [close] it deterministically; the segmenter holds a native interpreter plus the 6 MB model.
 *
 * [prepare] tries the GPU delegate first and falls back to CPU, since GPU delegate support varies
 * by device (and is generally unavailable on emulators).
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

  @Volatile private var closed = false

  /** Whether [prepare] ended up with the GPU delegate. */
  @Volatile
  var isGpuAccelerated: Boolean = false
    private set

  /**
   * Creates the segmenter if it doesn't exist yet, preferring the GPU delegate with a CPU
   * fallback. Idempotent. Throws [SegmentationException] when neither delegate works.
   */
  suspend fun prepare() {
    withContext(dispatcher) {
      if (closed || segmenter != null) {
        return@withContext
      }
      try {
        segmenter = createSegmenter(Delegate.GPU)
        isGpuAccelerated = true
      } catch (e: RuntimeException) {
        try {
          segmenter = createSegmenter(Delegate.CPU)
          isGpuAccelerated = false
        } catch (e2: RuntimeException) {
          throw SegmentationException("Failed to initialize InteractiveSegmenter", e2)
        }
      }
    }
  }

  /**
   * Segments the object in [frame] at the normalized point ([x], [y]) and returns its confidence
   * mask. The point is submitted as a single-point positive brush stroke.
   *
   * Serialized on the engine thread. Throws [SegmentationException] on MediaPipe errors and
   * [CancellationException] when the engine is closed or unprepared.
   */
  suspend fun segment(frame: Bitmap, x: Float, y: Float): ConfidenceMask {
    return withContext(dispatcher) {
      val segmenter =
        this@SegmenterEngine.segmenter ?: throw CancellationException("SegmenterEngine is closed")
      try {
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
        toConfidenceMask(mask)
      } catch (e: RuntimeException) {
        throw SegmentationException("Segmentation failed", e)
      }
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
   * (VEC32F1-style) or 8-bit alpha buffer.
   */
  private fun toConfidenceMask(mask: MPImage): ConfidenceMask {
    val pixelCount = mask.width * mask.height
    val buffer = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder())
    return when (buffer.capacity()) {
      pixelCount * Float.SIZE_BYTES ->
        ConfidenceMask(buffer.asFloatBuffer(), mask.width, mask.height)
      pixelCount -> {
        val floats = FloatBuffer.allocate(pixelCount)
        for (i in 0 until pixelCount) {
          floats.put((buffer.get(i).toInt() and 0xFF) / 255f)
        }
        floats.rewind()
        ConfidenceMask(floats, mask.width, mask.height)
      }
      else ->
        throw SegmentationException(
          "Unexpected mask buffer: ${buffer.capacity()} bytes for $pixelCount pixels"
        )
    }
  }

  private companion object {
    const val MODEL_ASSET_PATH = "magic_touch.tflite"
  }
}
