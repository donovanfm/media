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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Pure geometry for sticker overlay placement.
 *
 * Placement happens in two coordinate spaces: the UI space of the video content rect (the
 * letterboxed area of the player box that actually shows video), where the user drags and pinches
 * a preview image, and the normalized [-1, 1] space of [androidx.media3.common.OverlaySettings]
 * background frame anchors, where the committed overlay is rendered by the effect pipeline. This
 * file owns all conversions between the two, plus gesture application and clamping, so that the
 * math is unit-testable without any Android or media3 dependencies.
 */

/**
 * UI-space transform of a sticker, local to the video content rect.
 *
 * [offset] is the top-left of the *unscaled* bitmap; [scale] is applied around the bitmap center
 * (matching the default Compose `graphicsLayer` transform origin used by the placement preview).
 */
internal data class StickerTransform(val offset: Offset = Offset.Zero, val scale: Float = 1f)

internal object StickerGeometry {

  const val MIN_SCALE = 0.1f
  const val MAX_SCALE = 5f

  /** Inputs for [androidx.media3.effect.StaticOverlaySettings]: anchors in [-1, 1] and a scale. */
  internal data class OverlayPlacement(val anchorX: Float, val anchorY: Float, val scale: Float)

  /**
   * Returns the aspect-fit (letter/pillar-boxed) rect of the video inside the player box, in UI
   * pixels.
   *
   * Returns the full box when the video size is unknown (zero) or the box is degenerate.
   * [pixelWidthHeightRatio] accounts for anamorphic video, matching how the player surface scales
   * its content.
   */
  fun videoContentRect(
    playerBoxSize: Size,
    videoWidth: Int,
    videoHeight: Int,
    pixelWidthHeightRatio: Float,
  ): Rect {
    if (
      videoWidth <= 0 ||
        videoHeight <= 0 ||
        pixelWidthHeightRatio <= 0f ||
        playerBoxSize.width <= 0f ||
        playerBoxSize.height <= 0f
    ) {
      return Rect(Offset.Zero, playerBoxSize)
    }
    val displayAspectRatio = videoWidth * pixelWidthHeightRatio / videoHeight
    val boxAspectRatio = playerBoxSize.width / playerBoxSize.height
    return if (displayAspectRatio >= boxAspectRatio) {
      // Video is wider than the box: bars above and below.
      val contentHeight = playerBoxSize.width / displayAspectRatio
      val top = (playerBoxSize.height - contentHeight) / 2f
      Rect(0f, top, playerBoxSize.width, top + contentHeight)
    } else {
      // Video is narrower than the box: bars left and right.
      val contentWidth = playerBoxSize.height * displayAspectRatio
      val left = (playerBoxSize.width - contentWidth) / 2f
      Rect(left, 0f, left + contentWidth, playerBoxSize.height)
    }
  }

  /** Returns a transform that centers the unscaled bitmap inside [bounds] at scale 1. */
  fun centeredTransform(bitmapWidth: Int, bitmapHeight: Int, bounds: Size): StickerTransform =
    StickerTransform(
      offset = Offset((bounds.width - bitmapWidth) / 2f, (bounds.height - bitmapHeight) / 2f),
      scale = 1f,
    )

  /**
   * Applies one pan+zoom gesture event atomically, zooming about [centroid].
   *
   * The scale is clamped to [MIN_SCALE] and to the largest scale at which the sticker still fits
   * inside [bounds] (capped at [MAX_SCALE]); the resulting center is clamped so the scaled sticker
   * stays fully inside [bounds]. Applying scale and offset in a single step avoids the
   * inconsistent double-clamping that occurs when pan and zoom are handled as separate state
   * updates.
   */
  fun applyGesture(
    current: StickerTransform,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
    bounds: Size,
  ): StickerTransform {
    val maxScale =
      minOf(MAX_SCALE, bounds.width / bitmapWidth, bounds.height / bitmapHeight)
        .coerceAtLeast(MIN_SCALE)
    val newScale = (current.scale * zoom).coerceIn(MIN_SCALE, maxScale)
    // Use the clamp-aware zoom so a clamped scale doesn't drag the center around.
    val appliedZoom = newScale / current.scale
    val halfSize = Offset(bitmapWidth / 2f, bitmapHeight / 2f)
    val center = current.offset + halfSize
    // Zoom about the gesture centroid, then pan.
    val newCenter = centroid + (center - centroid) * appliedZoom + pan
    val clampedCenter =
      Offset(
        clampCenterAxis(newCenter.x, bitmapWidth * newScale / 2f, bounds.width),
        clampCenterAxis(newCenter.y, bitmapHeight * newScale / 2f, bounds.height),
      )
    return StickerTransform(offset = clampedCenter - halfSize, scale = newScale)
  }

  /**
   * Converts a committed transform to overlay settings inputs.
   *
   * Anchors are the sticker center as a fraction of [bounds] mapped to [-1, 1] with Y flipped
   * (OverlaySettings anchors are center-origin, Y-up). The returned scale converts the UI scale
   * into video-pixel space (`uiScale * videoPixelWidth / bounds.width`) so the rendered sticker
   * size matches the preview; when the video resolution is unknown ([videoPixelWidth] <= 0) it
   * falls back to the raw UI scale. Anisotropic pixel aspect ratios are ignored for the scale —
   * an acceptable approximation for a demo.
   */
  fun toOverlayPlacement(
    transform: StickerTransform,
    bitmapWidth: Int,
    bitmapHeight: Int,
    bounds: Size,
    videoPixelWidth: Int,
  ): OverlayPlacement {
    val centerX = transform.offset.x + bitmapWidth / 2f
    val centerY = transform.offset.y + bitmapHeight / 2f
    val anchorX = -1f + (centerX / bounds.width) * 2f
    val anchorY = 1f - (centerY / bounds.height) * 2f
    val scale =
      if (videoPixelWidth > 0) {
        transform.scale * videoPixelWidth / bounds.width
      } else {
        transform.scale
      }
    return OverlayPlacement(anchorX, anchorY, scale)
  }

  /** Clamps a center coordinate so [halfExtent] fits inside [0, boundsExtent] on this axis. */
  private fun clampCenterAxis(center: Float, halfExtent: Float, boundsExtent: Float): Float {
    val min = halfExtent
    val max = boundsExtent - halfExtent
    // Degenerate case: the scaled sticker is larger than the bounds on this axis.
    return if (min > max) boundsExtent / 2f else center.coerceIn(min, max)
  }
}
