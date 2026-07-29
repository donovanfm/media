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
 * A [BitmapOverlay] that plays a recorded animated sticker in a loop.
 *
 * The first [getBitmap] call baselines the video's presentation time, so the loop starts at the
 * sticker's first frame wherever playback begins; [FrameTimeline] handles wraparound including
 * seeks behind the baseline. BitmapOverlay re-uploads its texture only when the returned instance
 * changes, so returning the same frame between animation steps costs one reference compare, and a
 * frame advance costs one texture upload at the sticker's (≤512px) size.
 *
 * All frames must share the same dimensions (guaranteed by [StickerFrameRecorder]'s constant-size
 * composition) so overlay anchoring stays stable. Instances are used on media3's GL thread; the
 * mutable baseline is confined to that thread.
 */
@OptIn(UnstableApi::class)
internal class AnimatedStickerOverlay(
  frames: List<Bitmap>,
  timestampsUs: LongArray,
  durationUs: Long,
  private val settings: StaticOverlaySettings,
) : BitmapOverlay() {

  private val frames: ImmutableList<Bitmap> = ImmutableList.copyOf(frames)
  private val timeline = FrameTimeline(timestampsUs, durationUs)

  // Confined to the GL thread that calls getBitmap.
  private var firstPresentationTimeUs = C.TIME_UNSET

  override fun getBitmap(presentationTimeUs: Long): Bitmap {
    if (firstPresentationTimeUs == C.TIME_UNSET) {
      firstPresentationTimeUs = presentationTimeUs
    }
    return frames[timeline.frameIndexAt(presentationTimeUs - firstPresentationTimeUs)]
  }

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
