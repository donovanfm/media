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
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.google.common.collect.ImmutableList

/**
 * A [BitmapOverlay] that animates a placed sticker in either or both of two ways: swapping
 * recorded bitmap frames over time (looping via [FrameTimeline]) and modulating the overlay
 * settings with a [StickerAnimation] preset (rotation/scale/anchor per presentation time, at zero
 * texture-upload cost).
 *
 * The first call baselines the video's presentation time, so animations start at their beginning
 * wherever playback begins; [FrameTimeline] and [modulationAt] both handle wraparound including
 * seeks behind the baseline. BitmapOverlay re-uploads its texture only when the returned instance
 * changes, so a single-frame sticker with a settings preset never re-uploads at all.
 *
 * Instances are used on media3's GL thread; the mutable baseline is confined to that thread.
 */
@OptIn(UnstableApi::class)
internal class AnimatedStickerOverlay(
  frames: List<Bitmap>,
  timestampsUs: LongArray,
  durationUs: Long,
  private val animation: StickerAnimation,
  private val anchorX: Float,
  private val anchorY: Float,
  private val scale: Float,
) : BitmapOverlay() {

  private val frames: ImmutableList<Bitmap> = ImmutableList.copyOf(frames)
  private val timeline = FrameTimeline(timestampsUs, durationUs)
  private val staticSettings = settingsFor(AnimationModulation.IDENTITY)

  // Confined to the GL thread that calls getBitmap/getOverlaySettings.
  private var firstPresentationTimeUs = C.TIME_UNSET

  override fun getBitmap(presentationTimeUs: Long): Bitmap =
    frames[timeline.frameIndexAt(elapsedUs(presentationTimeUs))]

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
    if (animation == StickerAnimation.NONE) {
      return staticSettings
    }
    return settingsFor(animation.modulationAt(elapsedUs(presentationTimeUs)))
  }

  private fun elapsedUs(presentationTimeUs: Long): Long {
    if (firstPresentationTimeUs == C.TIME_UNSET) {
      firstPresentationTimeUs = presentationTimeUs
    }
    return presentationTimeUs - firstPresentationTimeUs
  }

  private fun settingsFor(modulation: AnimationModulation): StaticOverlaySettings =
    StaticOverlaySettings.Builder()
      .setBackgroundFrameAnchor(
        (anchorX + modulation.anchorOffsetX).coerceIn(-1f, 1f),
        (anchorY + modulation.anchorOffsetY).coerceIn(-1f, 1f),
      )
      .setScale(scale * modulation.scaleFactor, scale * modulation.scaleFactor)
      .setRotationDegrees(modulation.rotationDegrees)
      .build()

  companion object {
    /** Loop length used when a static bitmap is animated only by a settings preset. */
    private const val SINGLE_FRAME_DURATION_US = 1_000_000L

    /** Wraps a static sticker bitmap so a [StickerAnimation] preset can animate its placement. */
    fun forStaticBitmap(
      bitmap: Bitmap,
      animation: StickerAnimation,
      anchorX: Float,
      anchorY: Float,
      scale: Float,
    ): AnimatedStickerOverlay =
      AnimatedStickerOverlay(
        listOf(bitmap),
        longArrayOf(0),
        SINGLE_FRAME_DURATION_US,
        animation,
        anchorX,
        anchorY,
        scale,
      )
  }
}
