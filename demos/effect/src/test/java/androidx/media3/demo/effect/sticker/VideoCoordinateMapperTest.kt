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
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [VideoCoordinateMapper]. */
@RunWith(JUnit4::class)
class VideoCoordinateMapperTest {

  private val tolerance = 1e-4f

  @Test
  fun viewToNormalizedVideo_exactFit_mapsCorners() {
    val topLeft =
      VideoCoordinateMapper.viewToNormalizedVideo(320f, 180f, 1600, 900, tapX = 0f, tapY = 0f)
    val bottomRight =
      VideoCoordinateMapper.viewToNormalizedVideo(320f, 180f, 1600, 900, tapX = 320f, tapY = 180f)

    assertThat(topLeft).isNotNull()
    assertThat(topLeft!!.x).isWithin(tolerance).of(0f)
    assertThat(topLeft.y).isWithin(tolerance).of(0f)
    assertThat(bottomRight).isNotNull()
    assertThat(bottomRight!!.x).isWithin(tolerance).of(1f)
    assertThat(bottomRight.y).isWithin(tolerance).of(1f)
  }

  @Test
  fun viewToNormalizedVideo_letterboxed_mapsVideoCenter() {
    // 16:9 video in a square 400x400 view: content is 400x225, bars of 87.5 above and below.
    val center =
      VideoCoordinateMapper.viewToNormalizedVideo(400f, 400f, 1600, 900, tapX = 200f, tapY = 200f)

    assertThat(center).isNotNull()
    assertThat(center!!.x).isWithin(tolerance).of(0.5f)
    assertThat(center.y).isWithin(tolerance).of(0.5f)
  }

  @Test
  fun viewToNormalizedVideo_letterboxed_tapOnBar_returnsNull() {
    // Bars occupy y < 87.5 and y > 312.5.
    val onTopBar =
      VideoCoordinateMapper.viewToNormalizedVideo(400f, 400f, 1600, 900, tapX = 200f, tapY = 50f)

    assertThat(onTopBar).isNull()
  }

  @Test
  fun viewToNormalizedVideo_pillarboxed_mapsAndRejects() {
    // 9:16 video in a 400x400 view: content is 225x400, bars of 87.5 left and right.
    val inVideo =
      VideoCoordinateMapper.viewToNormalizedVideo(400f, 400f, 900, 1600, tapX = 200f, tapY = 100f)
    val onLeftBar =
      VideoCoordinateMapper.viewToNormalizedVideo(400f, 400f, 900, 1600, tapX = 50f, tapY = 200f)

    assertThat(inVideo).isNotNull()
    assertThat(inVideo!!.x).isWithin(tolerance).of(0.5f)
    assertThat(inVideo.y).isWithin(tolerance).of(0.25f)
    assertThat(onLeftBar).isNull()
  }

  @Test
  fun viewToNormalizedVideo_unknownDimensions_returnsNull() {
    assertThat(VideoCoordinateMapper.viewToNormalizedVideo(0f, 400f, 1600, 900, 10f, 10f)).isNull()
    assertThat(VideoCoordinateMapper.viewToNormalizedVideo(400f, 400f, 0, 900, 10f, 10f)).isNull()
  }
}
