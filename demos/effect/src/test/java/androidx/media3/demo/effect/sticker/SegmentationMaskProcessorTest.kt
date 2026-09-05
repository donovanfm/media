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

import com.google.common.truth.Truth.assertThat
import java.nio.FloatBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [SegmentationMaskProcessor]. */
@RunWith(JUnit4::class)
class SegmentationMaskProcessorTest {

  // --- toAlphaMask ---

  @Test
  fun toAlphaMask_scalesConfidenceToAlpha() {
    // 2x2: [1.0, 0.5; 0.2, 0.05], floor 0.1.
    val confidence = FloatBuffer.wrap(floatArrayOf(1f, 0.5f, 0.2f, 0.05f))

    val mask = SegmentationMaskProcessor.toAlphaMask(confidence, 2, 2, minConfidence = 0.1f)

    assertThat(mask.alpha[0].toInt() and 0xFF).isEqualTo(255)
    assertThat(mask.alpha[1].toInt() and 0xFF).isEqualTo(128) // round(0.5 * 255)
    assertThat(mask.alpha[2].toInt() and 0xFF).isEqualTo(51) // round(0.2 * 255)
    assertThat(mask.alpha[3].toInt() and 0xFF).isEqualTo(0) // below floor
  }

  @Test
  fun toAlphaMask_computesBboxOfPixelsAboveFloor() {
    // 4x3, object occupies (1,1)..(2,2).
    val values = FloatArray(12)
    values[1 * 4 + 1] = 0.9f
    values[1 * 4 + 2] = 0.8f
    values[2 * 4 + 1] = 0.7f
    values[2 * 4 + 2] = 0.6f

    val mask = SegmentationMaskProcessor.toAlphaMask(FloatBuffer.wrap(values), 4, 3)

    val bbox = mask.bbox
    assertThat(bbox).isNotNull()
    assertThat(bbox!!.left).isEqualTo(1)
    assertThat(bbox.top).isEqualTo(1)
    assertThat(bbox.right).isEqualTo(2)
    assertThat(bbox.bottom).isEqualTo(2)
    assertThat(bbox.width).isEqualTo(2)
    assertThat(bbox.height).isEqualTo(2)
  }

  @Test
  fun toAlphaMask_singlePixel_bboxIsThatPixel() {
    val values = FloatArray(9)
    values[4] = 1f // center of 3x3

    val mask = SegmentationMaskProcessor.toAlphaMask(FloatBuffer.wrap(values), 3, 3)

    assertThat(mask.bbox).isEqualTo(SegmentationMaskProcessor.Bbox(1, 1, 1, 1))
  }

  @Test
  fun toAlphaMask_fullFrame_bboxIsWholeMask() {
    val values = FloatArray(6) { 1f }

    val mask = SegmentationMaskProcessor.toAlphaMask(FloatBuffer.wrap(values), 3, 2)

    assertThat(mask.bbox).isEqualTo(SegmentationMaskProcessor.Bbox(0, 0, 2, 1))
  }

  @Test
  fun toAlphaMask_emptyMask_bboxIsNull() {
    val values = FloatArray(4) { 0.01f }

    val mask = SegmentationMaskProcessor.toAlphaMask(FloatBuffer.wrap(values), 2, 2)

    assertThat(mask.bbox).isNull()
  }

  // --- toPreviewPixels ---

  @Test
  fun toPreviewPixels_tintsWithScaledAlpha() {
    val confidence = FloatBuffer.wrap(floatArrayOf(1f, 0f))
    val mask = SegmentationMaskProcessor.toAlphaMask(confidence, 2, 1)

    val pixels = SegmentationMaskProcessor.toPreviewPixels(mask, tintRgb = 0x0000FF, maxAlpha = 150)

    assertThat(pixels[0]).isEqualTo((150 shl 24) or 0x0000FF)
    assertThat(pixels[1]).isEqualTo(0)
  }

  // --- cutout ---

  @Test
  fun cutout_cropsToBboxAndAppliesMaskAlpha() {
    // 3x3 frame of opaque red; mask selects the middle column at full and half confidence.
    val frame = IntArray(9) { 0xFFFF0000.toInt() }
    val values = FloatArray(9)
    values[1] = 1f // (1, 0)
    values[4] = 0.5f // (1, 1)
    val mask = SegmentationMaskProcessor.toAlphaMask(FloatBuffer.wrap(values), 3, 3)

    val cutout = SegmentationMaskProcessor.cutout(frame, 3, mask)

    assertThat(cutout).isNotNull()
    assertThat(cutout!!.bbox).isEqualTo(SegmentationMaskProcessor.Bbox(1, 0, 1, 1))
    assertThat(cutout.pixels).hasLength(2)
    assertThat(cutout.pixels[0]).isEqualTo((255 shl 24) or 0x00FF0000)
    assertThat(cutout.pixels[1]).isEqualTo((128 shl 24) or 0x00FF0000)
  }

  @Test
  fun cutout_emptyMask_returnsNull() {
    val frame = IntArray(4)
    val mask = SegmentationMaskProcessor.toAlphaMask(FloatBuffer.wrap(FloatArray(4)), 2, 2)

    assertThat(SegmentationMaskProcessor.cutout(frame, 2, mask)).isNull()
  }

  @Test(expected = IllegalArgumentException::class)
  fun cutout_mismatchedFrameSize_throws() {
    val values = FloatArray(4) { 1f }
    val mask = SegmentationMaskProcessor.toAlphaMask(FloatBuffer.wrap(values), 2, 2)

    SegmentationMaskProcessor.cutout(IntArray(9), 3, mask)
  }

  // --- opaqueBounds / cropPixels ---

  @Test
  fun opaqueBounds_transparentBorder_returnsTightBox() {
    // 4x4, opaque pixels only at (1,1) and (2,2).
    val pixels = IntArray(16)
    pixels[1 * 4 + 1] = 0xFFFF0000.toInt()
    pixels[2 * 4 + 2] = 0x01000000 // barely visible still counts as non-transparent

    val bounds = SegmentationMaskProcessor.opaqueBounds(pixels, 4, 4)

    assertThat(bounds).isEqualTo(SegmentationMaskProcessor.Bbox(1, 1, 2, 2))
  }

  @Test
  fun opaqueBounds_noTransparentBorder_returnsFullBox() {
    val pixels = IntArray(6) { 0xFF00FF00.toInt() }

    val bounds = SegmentationMaskProcessor.opaqueBounds(pixels, 3, 2)

    assertThat(bounds).isEqualTo(SegmentationMaskProcessor.Bbox(0, 0, 2, 1))
  }

  @Test
  fun opaqueBounds_fullyTransparent_returnsNull() {
    assertThat(SegmentationMaskProcessor.opaqueBounds(IntArray(9), 3, 3)).isNull()
  }

  @Test
  fun cropPixels_extractsBoxContents() {
    // 3x3 with distinct values; crop the center-right 2x1 box.
    val pixels = IntArray(9) { it }
    val bbox = SegmentationMaskProcessor.Bbox(1, 1, 2, 1)

    val cropped = SegmentationMaskProcessor.cropPixels(pixels, 3, bbox)

    assertThat(cropped).asList().containsExactly(4, 5).inOrder()
  }
}
