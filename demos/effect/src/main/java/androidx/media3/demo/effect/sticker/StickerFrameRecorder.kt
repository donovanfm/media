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

import androidx.media3.demo.effect.sticker.SegmentationMaskProcessor.Bbox
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Accumulates per-frame cutouts during animated sticker recording and composes them into a
 * constant-size frame sequence.
 *
 * Each recorded frame stores only the bbox-cropped cutout (far smaller than the source frame).
 * [composeFrames] then re-renders every cutout onto a transparent canvas the size of the union of
 * all bounding boxes — the object moves naturally inside the sticker while the texture size stays
 * constant, which keeps overlay anchoring stable during playback. Memory is bounded by a frame
 * count cap and a byte budget. Pure array math, unit-testable on the JVM.
 */
internal class StickerFrameRecorder(
  private val maxFrames: Int = DEFAULT_MAX_FRAMES,
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

  private class RecordedFrame(val pixels: IntArray, val bbox: Bbox, val timestampUs: Long)

  private val frames = mutableListOf<RecordedFrame>()
  private val acceptedOpaqueCounts = mutableListOf<Int>()
  private var unionBbox: Bbox? = null
  private var recordedBytes = 0L

  val frameCount: Int
    get() = frames.size

  /** True once a cap is reached; further [addFrame] calls store nothing. */
  val isFull: Boolean
    get() = frames.size >= maxFrames || recordedBytes >= maxBytes

  /**
   * Cuts the object out of the frame with [mask] and stores it at [timestampUs]. Returns false —
   * storing nothing — when the mask is empty, the frame is an outlier, or a cap has been reached.
   */
  fun addFrame(
    framePixels: IntArray,
    frameWidth: Int,
    mask: SegmentationMaskProcessor.AlphaMask,
    timestampUs: Long,
  ): Boolean {
    if (isFull) {
      return false
    }
    val cutout = SegmentationMaskProcessor.cutout(framePixels, frameWidth, mask) ?: return false
    if (isOutlier(cutout.opaquePixelCount)) {
      return false
    }
    frames += RecordedFrame(cutout.pixels, cutout.bbox, timestampUs)
    acceptedOpaqueCounts += cutout.opaquePixelCount
    unionBbox = unionBbox?.union(cutout.bbox) ?: cutout.bbox
    recordedBytes += cutout.pixels.size * 4L
    return true
  }

  /**
   * Per-frame segmentation has no temporal consistency: an occasional frame loses the subject and
   * returns a near-empty (or whole-frame) mask, which would bake a blank or flashing frame into
   * the sticker. Frames whose visible area is far from the median of the accepted frames are
   * rejected; playback timestamps naturally hold the previous frame across the gap.
   */
  private fun isOutlier(opaquePixelCount: Int): Boolean {
    if (acceptedOpaqueCounts.size < MIN_FRAMES_FOR_OUTLIER_REJECTION) {
      return false
    }
    val median = acceptedOpaqueCounts.sorted()[acceptedOpaqueCounts.size / 2]
    return opaquePixelCount * OUTLIER_AREA_FACTOR < median ||
      opaquePixelCount > median * OUTLIER_AREA_FACTOR
  }

  /** A composed animation: constant-size ARGB frames with timestamps rebased to start at 0. */
  class ComposedAnimation(
    val frames: List<IntArray>,
    val width: Int,
    val height: Int,
    val timestampsUs: LongArray,
    val durationUs: Long,
  ) {

    /**
     * Returns this animation cropped to the tightest box containing every frame's non-transparent
     * pixels. All frames are cropped by the same rect, so the frame size stays constant (stable
     * overlay anchoring) and the object's motion within the sticker is preserved — only borders
     * that are transparent in EVERY frame are removed, letting the visible content reach the
     * video edges during placement.
     */
    fun trimmedToOpaqueBounds(): ComposedAnimation {
      var union: SegmentationMaskProcessor.Bbox? = null
      for (frame in frames) {
        val bounds = SegmentationMaskProcessor.opaqueBounds(frame, width, height) ?: continue
        union = union?.union(bounds) ?: bounds
      }
      val bounds = union ?: return this
      if (bounds.width == width && bounds.height == height) {
        return this
      }
      return ComposedAnimation(
        frames.map { SegmentationMaskProcessor.cropPixels(it, width, bounds) },
        bounds.width,
        bounds.height,
        timestampsUs,
        durationUs,
      )
    }
  }

  /**
   * Composes the recorded cutouts into constant-size frames, downscaled (nearest-neighbor) so
   * that neither dimension exceeds [maxDimension]. Each cutout keeps its position within the
   * union bounding box. The loop duration extends one average frame interval past the last frame.
   * Returns null when nothing was recorded.
   */
  fun composeFrames(maxDimension: Int = DEFAULT_COMPOSE_MAX_DIMENSION): ComposedAnimation? {
    val union = unionBbox ?: return null
    val scale = min(1f, maxDimension.toFloat() / max(union.width, union.height))
    val outWidth = max(1, (union.width * scale).roundToInt())
    val outHeight = max(1, (union.height * scale).roundToInt())
    val composedFrames =
      frames.map { frame ->
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
          val sourceY = union.top + min((y / scale).toInt(), union.height - 1)
          if (sourceY < frame.bbox.top || sourceY > frame.bbox.bottom) {
            continue
          }
          val frameRow = (sourceY - frame.bbox.top) * frame.bbox.width
          val outRow = y * outWidth
          for (x in 0 until outWidth) {
            val sourceX = union.left + min((x / scale).toInt(), union.width - 1)
            if (sourceX >= frame.bbox.left && sourceX <= frame.bbox.right) {
              out[outRow + x] = frame.pixels[frameRow + sourceX - frame.bbox.left]
            }
          }
        }
        out
      }
    val baseTimestampUs = frames.first().timestampUs
    val timestampsUs = LongArray(frames.size) { frames[it].timestampUs - baseTimestampUs }
    val averageFrameIntervalUs =
      if (frames.size > 1) {
        max(1L, timestampsUs.last() / (frames.size - 1))
      } else {
        SINGLE_FRAME_DURATION_US
      }
    return ComposedAnimation(
      composedFrames,
      outWidth,
      outHeight,
      timestampsUs,
      durationUs = timestampsUs.last() + averageFrameIntervalUs,
    )
  }

  companion object {
    const val DEFAULT_MAX_FRAMES = 90
    const val DEFAULT_MAX_BYTES = 64L shl 20
    const val DEFAULT_COMPOSE_MAX_DIMENSION = 512
    private const val SINGLE_FRAME_DURATION_US = 1_000_000L
    private const val MIN_FRAMES_FOR_OUTLIER_REJECTION = 2
    private const val OUTLIER_AREA_FACTOR = 4
  }
}
