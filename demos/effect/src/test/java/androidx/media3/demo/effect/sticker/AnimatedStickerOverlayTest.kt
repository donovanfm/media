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

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for [AnimatedStickerOverlay]. */
@RunWith(RobolectricTestRunner::class)
class AnimatedStickerOverlayTest {

  private val frames =
    List(3) { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) } // distinct instances

  // Frames at 0ms, 100ms, 200ms; loop is 300ms.
  private fun overlay(animation: StickerAnimation = StickerAnimation.NONE): AnimatedStickerOverlay =
    AnimatedStickerOverlay(
      frames,
      timestampsUs = longArrayOf(0, 100_000, 200_000),
      durationUs = 300_000,
      animation = animation,
      anchorX = 0.5f,
      anchorY = -0.25f,
      scale = 2f,
    )

  @Test
  fun getBitmap_baselinesFirstCall_startsAtFirstFrame() {
    val overlay = overlay()

    // Playback starting mid-stream must still begin the loop at frame 0.
    assertThat(overlay.getBitmap(5_000_000)).isSameInstanceAs(frames[0])
  }

  @Test
  fun getBitmap_advancesRelativeToBaseline() {
    val overlay = overlay()
    overlay.getBitmap(5_000_000)

    assertThat(overlay.getBitmap(5_000_000 + 100_000)).isSameInstanceAs(frames[1])
    assertThat(overlay.getBitmap(5_000_000 + 250_000)).isSameInstanceAs(frames[2])
  }

  @Test
  fun getBitmap_loops() {
    val overlay = overlay()
    overlay.getBitmap(0)

    assertThat(overlay.getBitmap(300_000)).isSameInstanceAs(frames[0])
    assertThat(overlay.getBitmap(300_000 + 150_000)).isSameInstanceAs(frames[1])
  }

  @Test
  fun getBitmap_sameInterval_returnsSameInstance() {
    // BitmapOverlay only re-uploads its texture when the returned instance changes, so
    // consecutive video frames within one sticker frame interval must get the same instance.
    val overlay = overlay()
    overlay.getBitmap(0)

    assertThat(overlay.getBitmap(16_000)).isSameInstanceAs(overlay.getBitmap(33_000))
  }

  @Test
  fun getOverlaySettings_noAnimation_returnsBasePlacement() {
    val overlay = overlay(StickerAnimation.NONE)

    val settings = overlay.getOverlaySettings(0)

    assertThat(settings.backgroundFrameAnchor.first).isEqualTo(0.5f)
    assertThat(settings.backgroundFrameAnchor.second).isEqualTo(-0.25f)
    assertThat(settings.scale.first).isEqualTo(2f)
    assertThat(settings.rotationDegrees).isEqualTo(0f)
    // Same instance every call: no per-frame settings allocation without a preset.
    assertThat(overlay.getOverlaySettings(123_456)).isSameInstanceAs(settings)
  }

  @Test
  fun getOverlaySettings_rock_alternatesRotationAroundBasePlacement() {
    val overlay = overlay(StickerAnimation.ROCK)
    overlay.getBitmap(1_000_000) // baseline at 1s

    val firstHalf = overlay.getOverlaySettings(1_000_000)
    val secondHalf = overlay.getOverlaySettings(1_000_000 + ROCK_HALF_PERIOD_US)

    assertThat(firstHalf.rotationDegrees).isEqualTo(-ROCK_ANGLE_DEGREES)
    assertThat(secondHalf.rotationDegrees).isEqualTo(ROCK_ANGLE_DEGREES)
    // The base placement is preserved while rotating.
    assertThat(firstHalf.backgroundFrameAnchor.first).isEqualTo(0.5f)
    assertThat(firstHalf.scale.first).isEqualTo(2f)
  }

  @Test
  fun forStaticBitmap_singleFrameWithPreset_animatesSettingsOnly() {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val overlay =
      AnimatedStickerOverlay.forStaticBitmap(
        bitmap,
        StickerAnimation.PULSE,
        anchorX = 0f,
        anchorY = 0f,
        scale = 1f,
      )

    assertThat(overlay.getBitmap(0)).isSameInstanceAs(bitmap)
    assertThat(overlay.getBitmap(5_000_000)).isSameInstanceAs(bitmap)
    val quarterPeriod = overlay.getOverlaySettings(PULSE_PERIOD_US / 4)
    assertThat(quarterPeriod.scale.first).isWithin(1e-4f).of(1f + PULSE_AMPLITUDE)
  }
}
