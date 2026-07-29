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

import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Pure pixel math for turning a MediaPipe confidence mask into sticker imagery.
 *
 * The segmenter produces a float confidence value in [0, 1] per pixel. This converts that directly
 * into an 8-bit alpha channel (soft edges preserved), computes the bounding box of the selected
 * object in the same pass, and cuts the object out of a video frame. Everything operates on plain
 * arrays so it is unit-testable on the JVM; bitmap conversion happens at the call sites.
 */
internal object SegmentationMaskProcessor {

  /** Inclusive bounding box of the non-transparent pixels of a mask. */
  data class Bbox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
      get() = right - left + 1

    val height: Int
      get() = bottom - top + 1

    /** Returns the smallest box containing both this box and [other]. */
    fun union(other: Bbox): Bbox =
      Bbox(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
      )
  }

  /**
   * Row-major 8-bit alpha mask (0..255 stored as unsigned bytes) with the bounding box of its
   * non-zero pixels, or a null [bbox] when nothing was selected.
   */
  class AlphaMask(val alpha: ByteArray, val width: Int, val height: Int, val bbox: Bbox?)

  /** A cutout: ARGB pixels of size [bbox].width x [bbox].height. */
  class Cutout(val pixels: IntArray, val bbox: Bbox)

  /**
   * Converts a confidence buffer to an [AlphaMask] in a single pass.
   *
   * Each pixel's alpha is `round(confidence * 255)` when the confidence reaches [minConfidence],
   * else 0. Confidence below the floor is treated as background noise; keeping the proportional
   * alpha above it preserves the mask's soft edges.
   */
  fun toAlphaMask(
    confidence: FloatBuffer,
    width: Int,
    height: Int,
    minConfidence: Float = 0.1f,
  ): AlphaMask {
    val alpha = ByteArray(width * height)
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE
    for (y in 0 until height) {
      val rowOffset = y * width
      for (x in 0 until width) {
        val value = confidence.get(rowOffset + x)
        if (value >= minConfidence) {
          alpha[rowOffset + x] = (value.coerceAtMost(1f) * 255f).roundToInt().toByte()
          if (x < left) left = x
          if (x > right) right = x
          if (y < top) top = y
          if (y > bottom) bottom = y
        }
      }
    }
    val bbox = if (right >= left && bottom >= top) Bbox(left, top, right, bottom) else null
    return AlphaMask(alpha, width, height, bbox)
  }

  /**
   * Returns ARGB pixels for an on-screen preview of [mask]: [tintRgb] (0xRRGGBB) with each pixel's
   * alpha scaled by the mask, at most [maxAlpha] so the video stays visible underneath.
   */
  fun toPreviewPixels(mask: AlphaMask, tintRgb: Int = 0x0000FF, maxAlpha: Int = 150): IntArray {
    val pixels = IntArray(mask.width * mask.height)
    for (i in pixels.indices) {
      val maskAlpha = mask.alpha[i].toInt() and 0xFF
      val alpha = maskAlpha * maxAlpha / 255
      if (alpha > 0) {
        pixels[i] = (alpha shl 24) or tintRgb
      }
    }
    return pixels
  }

  /**
   * Cuts the masked object out of a frame: pixels are cropped to the mask's bounding box, keeping
   * the frame's RGB with alpha taken from the mask. Returns null when the mask is empty.
   *
   * [framePixels] must be row-major ARGB with the same dimensions as the mask.
   */
  fun cutout(framePixels: IntArray, frameWidth: Int, mask: AlphaMask): Cutout? {
    val bbox = mask.bbox ?: return null
    require(framePixels.size == mask.width * mask.height) {
      "Frame (${framePixels.size} px) doesn't match mask (${mask.width}x${mask.height})"
    }
    require(frameWidth == mask.width) { "Frame width $frameWidth != mask width ${mask.width}" }
    val out = IntArray(bbox.width * bbox.height)
    for (y in 0 until bbox.height) {
      val srcRow = (bbox.top + y) * frameWidth
      val dstRow = y * bbox.width
      for (x in 0 until bbox.width) {
        val srcIndex = srcRow + bbox.left + x
        val alpha = mask.alpha[srcIndex].toInt() and 0xFF
        if (alpha > 0) {
          out[dstRow + x] = (alpha shl 24) or (framePixels[srcIndex] and 0x00FFFFFF)
        }
      }
    }
    return Cutout(out, bbox)
  }
}
