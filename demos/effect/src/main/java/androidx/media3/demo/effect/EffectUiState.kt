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
package androidx.media3.demo.effect

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.media3.demo.effect.sticker.AnimatedSticker
import androidx.media3.demo.effect.sticker.StickerAnimation
import androidx.media3.demo.effect.sticker.StickerAsset
import androidx.media3.demo.effect.ui.COLORS
import com.google.common.collect.ImmutableList
import java.util.UUID

/** UI state for the effect demo application. */
internal data class EffectUiState(
  val effectsEnabled: Boolean = false,
  val effectsChanged: Boolean = false,
  val contrastChecked: Boolean = false,
  val contrastValue: Float = 0f,
  val confettiOverlayChecked: Boolean = false,
  val textOverlayChecked: Boolean = false,
  val clockOverlayChecked: Boolean = false,
  val lottieOverlayChecked: Boolean = false,
  val textOverlayText: String? = null,
  val textOverlayColor: Color = COLORS[0],
  val textOverlayAlpha: Float = 1f,
  val lottieOverlayName: String? = null,
  val lottieEffectsLoaded: Boolean = false,
  val lottieOverlayOptions: ImmutableList<String> = ImmutableList.of(),
  val stickerOverlayChecked: Boolean = false,
  val stickerAssetsLoaded: Boolean = false,
  val stickerAssets: ImmutableList<StickerAsset> = ImmutableList.of(),
  val selectedStickerAssetId: String? = null,
  val selectedStickerAnimation: StickerAnimation = StickerAnimation.NONE,
  /** False when the segmentation model isn't bundled (offline/skipped build). */
  val stickerCreationAvailable: Boolean = true,
  val placedStickers: ImmutableList<PlacedSticker> = ImmutableList.of(),
  val stickerPlacement: StickerPlacement = StickerPlacement.Inactive,
  val playerBoxSize: Size = Size.Zero,
  val errorMessage: String? = null,
)

/**
 * A sticker committed to the video via drag/pinch placement.
 *
 * [transform] is in the UI space of [contentRect] (the letterboxed video area of the player box at
 * commit time); [videoPixelWidth] snapshots the video resolution so the overlay scale can be made
 * WYSIWYG relative to the preview. See [StickerGeometry.toOverlayPlacement].
 */
internal data class PlacedSticker(
  val id: UUID = UUID.randomUUID(),
  val assetName: String,
  val bitmap: Bitmap,
  val transform: StickerTransform,
  val contentRect: Rect,
  val videoPixelWidth: Int,
  /** Frames and timeline for animated stickers; null renders [bitmap] statically. */
  val animated: AnimatedSticker? = null,
  /** Preset placement animation applied on top of the placed position. */
  val animation: StickerAnimation = StickerAnimation.NONE,
)

/** Whether the user is currently positioning a sticker over the player. */
internal sealed interface StickerPlacement {
  data object Inactive : StickerPlacement

  /**
   * A sticker is being positioned. [original] is non-null when re-editing a committed sticker, so
   * cancelling can restore it unchanged.
   */
  data class Placing(
    val original: PlacedSticker?,
    val assetName: String,
    val bitmap: Bitmap,
    val transform: StickerTransform,
    val contentRect: Rect,
    val videoPixelWidth: Int,
    /** Frames and timeline when placing an animated sticker; the preview shows [bitmap]. */
    val animated: AnimatedSticker? = null,
    /** Preset placement animation the sticker will be committed with. */
    val animation: StickerAnimation = StickerAnimation.NONE,
  ) : StickerPlacement
}
