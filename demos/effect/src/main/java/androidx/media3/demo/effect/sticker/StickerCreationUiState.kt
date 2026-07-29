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

/** Which kind of sticker a gesture produces. */
internal enum class StickerMode {
  /** Long-press a paused frame to cut a single image. */
  STATIC,
  /** Hold an object while the video plays; frames are recorded until the finger lifts. */
  ANIMATED,
}

/** The sticker creation screen's current phase. */
internal sealed interface CreationPhase {
  /** No video yet; the user needs to pick one. */
  data object AwaitingVideo : CreationPhase

  /** Video is playing; waiting for the user to long-press an object. */
  data object Playing : CreationPhase

  /** A segmentation is running on the paused frame. */
  data object Segmenting : CreationPhase

  /** A cutout is ready: [maskPreview] overlays the paused frame, [cutout] is the sticker. */
  data class StaticPreview(val maskPreview: Bitmap, val cutout: Bitmap) : CreationPhase

  /** An animated recording is in progress; [frameCount] frames captured so far. */
  data class Recording(val frameCount: Int) : CreationPhase

  /** An animated recording is ready to preview and save. */
  data class AnimatedPreview(val animation: AnimatedSticker) : CreationPhase

  /** The sticker is being persisted. */
  data object Saving : CreationPhase
}

/** UI state for the sticker creation screen. */
internal data class StickerCreationUiState(
  val mode: StickerMode = StickerMode.STATIC,
  val phase: CreationPhase = CreationPhase.AwaitingVideo,
  val segmenterReady: Boolean = false,
  val stickerName: String = "",
  val videoAspectRatio: Float = 0f, // width/height including pixel aspect; 0 when unknown
  val errorMessage: String? = null,
)
