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

/** Unit tests for [StickerFrameRecorder]. */
@RunWith(JUnit4::class)
class StickerFrameRecorderTest {

  private val frameWidth = 8
  private val frameHeight = 8

  /** An opaque single-color frame. */
  private fun framePixels(color: Int): IntArray =
    IntArray(frameWidth * frameHeight) { (0xFF shl 24) or color }

  /** A full-confidence mask covering exactly [left]..[right] x [top]..[bottom]. */
  private fun mask(left: Int, top: Int, right: Int, bottom: Int): SegmentationMaskProcessor.AlphaMask {
    val confidence = FloatArray(frameWidth * frameHeight)
    for (y in top..bottom) {
      for (x in left..right) {
        confidence[y * frameWidth + x] = 1f
      }
    }
    return SegmentationMaskProcessor.toAlphaMask(
      FloatBuffer.wrap(confidence),
      frameWidth,
      frameHeight,
    )
  }

  private fun emptyMask(): SegmentationMaskProcessor.AlphaMask =
    SegmentationMaskProcessor.toAlphaMask(
      FloatBuffer.wrap(FloatArray(frameWidth * frameHeight)),
      frameWidth,
      frameHeight,
    )

  @Test
  fun addFrame_emptyMask_storesNothing() {
    val recorder = StickerFrameRecorder()

    val added = recorder.addFrame(framePixels(0xFF0000), frameWidth, emptyMask(), 0)

    assertThat(added).isFalse()
    assertThat(recorder.frameCount).isEqualTo(0)
    assertThat(recorder.composeFrames()).isNull()
  }

  @Test
  fun addFrame_pastFrameCap_storesNothing() {
    val recorder = StickerFrameRecorder(maxFrames = 2)

    assertThat(recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 0)).isTrue()
    assertThat(recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 10)).isTrue()
    assertThat(recorder.isFull).isTrue()
    assertThat(recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 20)).isFalse()
    assertThat(recorder.frameCount).isEqualTo(2)
  }

  @Test
  fun addFrame_pastByteBudget_storesNothing() {
    // A 2x2 cutout is 16 bytes; a 15-byte budget is exhausted after one frame.
    val recorder = StickerFrameRecorder(maxBytes = 15)

    assertThat(recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 0)).isTrue()
    assertThat(recorder.isFull).isTrue()
    assertThat(recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 10)).isFalse()
  }

  @Test
  fun composeFrames_usesUnionBboxAndKeepsCutoutPositions() {
    val recorder = StickerFrameRecorder()
    // Frame 1: object at (1,1)..(2,2); frame 2: object moved to (3,3)..(4,4).
    recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(1, 1, 2, 2), 0)
    recorder.addFrame(framePixels(0x00FF00), frameWidth, mask(3, 3, 4, 4), 100_000)

    val composed = recorder.composeFrames()

    assertThat(composed).isNotNull()
    // Union is (1,1)..(4,4) -> 4x4 output.
    assertThat(composed!!.width).isEqualTo(4)
    assertThat(composed.height).isEqualTo(4)
    val red = (0xFF shl 24) or 0xFF0000
    val green = (0xFF shl 24) or 0x00FF00
    // Frame 0: red object occupies union-local (0,0)..(1,1); rest transparent.
    assertThat(composed.frames[0][0]).isEqualTo(red)
    assertThat(composed.frames[0][1 * 4 + 1]).isEqualTo(red)
    assertThat(composed.frames[0][2 * 4 + 2]).isEqualTo(0)
    // Frame 1: green object occupies union-local (2,2)..(3,3); top-left transparent.
    assertThat(composed.frames[1][0]).isEqualTo(0)
    assertThat(composed.frames[1][2 * 4 + 2]).isEqualTo(green)
    assertThat(composed.frames[1][3 * 4 + 3]).isEqualTo(green)
  }

  @Test
  fun composeFrames_rebasesTimestampsAndExtendsDuration() {
    val recorder = StickerFrameRecorder()
    recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 500_000)
    recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 600_000)
    recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 800_000)

    val composed = recorder.composeFrames()

    assertThat(composed!!.timestampsUs).asList().containsExactly(0L, 100_000L, 300_000L).inOrder()
    // Average interval is 150ms -> loop is 450ms.
    assertThat(composed.durationUs).isEqualTo(450_000)
  }

  @Test
  fun composeFrames_downscalesToMaxDimension() {
    val recorder = StickerFrameRecorder()
    recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 7, 7), 0)

    val composed = recorder.composeFrames(maxDimension = 4)

    assertThat(composed!!.width).isEqualTo(4)
    assertThat(composed.height).isEqualTo(4)
    // Nearest-neighbor downscale of a solid object stays solid.
    val red = (0xFF shl 24) or 0xFF0000
    assertThat(composed.frames[0].all { it == red }).isTrue()
  }

  @Test
  fun composeFrames_singleFrame_getsFixedDuration() {
    val recorder = StickerFrameRecorder()
    recorder.addFrame(framePixels(0xFF0000), frameWidth, mask(0, 0, 1, 1), 42)

    val composed = recorder.composeFrames()

    assertThat(composed!!.timestampsUs).asList().containsExactly(0L)
    assertThat(composed.durationUs).isEqualTo(1_000_000)
  }
}
