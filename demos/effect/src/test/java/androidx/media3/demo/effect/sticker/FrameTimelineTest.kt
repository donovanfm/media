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

/** Unit tests for [FrameTimeline]. */
@RunWith(JUnit4::class)
class FrameTimelineTest {

  // Frames at 0ms, 100ms, 250ms; loop is 350ms.
  private val timeline =
    FrameTimeline(timestampsUs = longArrayOf(0, 100_000, 250_000), durationUs = 350_000)

  @Test
  fun frameIndexAt_boundaryTimestamps_selectTheirFrame() {
    assertThat(timeline.frameIndexAt(0)).isEqualTo(0)
    assertThat(timeline.frameIndexAt(100_000)).isEqualTo(1)
    assertThat(timeline.frameIndexAt(250_000)).isEqualTo(2)
  }

  @Test
  fun frameIndexAt_betweenTimestamps_holdsPreviousFrame() {
    assertThat(timeline.frameIndexAt(99_999)).isEqualTo(0)
    assertThat(timeline.frameIndexAt(180_000)).isEqualTo(1)
    assertThat(timeline.frameIndexAt(349_999)).isEqualTo(2)
  }

  @Test
  fun frameIndexAt_loops() {
    assertThat(timeline.frameIndexAt(350_000)).isEqualTo(0) // exactly one loop
    assertThat(timeline.frameIndexAt(350_000 + 100_000)).isEqualTo(1)
    assertThat(timeline.frameIndexAt(2 * 350_000 + 250_000)).isEqualTo(2)
  }

  @Test
  fun frameIndexAt_negativeElapsed_wrapsAround() {
    // -50ms == 300ms into the loop -> frame at 250ms.
    assertThat(timeline.frameIndexAt(-50_000)).isEqualTo(2)
  }

  @Test
  fun frameIndexAt_singleFrame_alwaysZero() {
    val single = FrameTimeline(longArrayOf(0), durationUs = 1_000_000)

    assertThat(single.frameIndexAt(0)).isEqualTo(0)
    assertThat(single.frameIndexAt(999_999)).isEqualTo(0)
    assertThat(single.frameIndexAt(123_456_789)).isEqualTo(0)
  }

  @Test(expected = IllegalArgumentException::class)
  fun constructor_nonZeroFirstTimestamp_throws() {
    FrameTimeline(longArrayOf(10, 20), durationUs = 100)
  }

  @Test(expected = IllegalArgumentException::class)
  fun constructor_durationNotPastLastFrame_throws() {
    FrameTimeline(longArrayOf(0, 100), durationUs = 100)
  }
}
