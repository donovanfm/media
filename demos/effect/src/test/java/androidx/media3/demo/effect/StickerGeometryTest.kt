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
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [StickerGeometry]. */
@RunWith(JUnit4::class)
class StickerGeometryTest {

  private val tolerance = 1e-3f

  // --- videoContentRect ---

  @Test
  fun videoContentRect_widerVideo_letterboxesTopAndBottom() {
    val rect =
      StickerGeometry.videoContentRect(
        playerBoxSize = Size(400f, 400f),
        videoWidth = 1600,
        videoHeight = 900,
        pixelWidthHeightRatio = 1f,
      )

    assertThat(rect.left).isWithin(tolerance).of(0f)
    assertThat(rect.right).isWithin(tolerance).of(400f)
    assertThat(rect.top).isWithin(tolerance).of((400f - 225f) / 2f)
    assertThat(rect.height).isWithin(tolerance).of(225f)
  }

  @Test
  fun videoContentRect_narrowerVideo_pillarboxesLeftAndRight() {
    val rect =
      StickerGeometry.videoContentRect(
        playerBoxSize = Size(1000f, 500f),
        videoWidth = 160,
        videoHeight = 90,
        pixelWidthHeightRatio = 1f,
      )

    val expectedWidth = 500f * (160f / 90f)
    assertThat(rect.top).isWithin(tolerance).of(0f)
    assertThat(rect.bottom).isWithin(tolerance).of(500f)
    assertThat(rect.width).isWithin(tolerance).of(expectedWidth)
    assertThat(rect.left).isWithin(tolerance).of((1000f - expectedWidth) / 2f)
  }

  @Test
  fun videoContentRect_anamorphicVideo_respectsPixelAspectRatio() {
    // Square pixels would pillarbox this; PAR 2.0 makes it exactly fill the box.
    val rect =
      StickerGeometry.videoContentRect(
        playerBoxSize = Size(200f, 100f),
        videoWidth = 100,
        videoHeight = 100,
        pixelWidthHeightRatio = 2f,
      )

    assertThat(rect.left).isWithin(tolerance).of(0f)
    assertThat(rect.top).isWithin(tolerance).of(0f)
    assertThat(rect.width).isWithin(tolerance).of(200f)
    assertThat(rect.height).isWithin(tolerance).of(100f)
  }

  @Test
  fun videoContentRect_unknownVideoSize_returnsFullBox() {
    val rect =
      StickerGeometry.videoContentRect(
        playerBoxSize = Size(640f, 360f),
        videoWidth = 0,
        videoHeight = 0,
        pixelWidthHeightRatio = 1f,
      )

    assertThat(rect.left).isWithin(tolerance).of(0f)
    assertThat(rect.top).isWithin(tolerance).of(0f)
    assertThat(rect.width).isWithin(tolerance).of(640f)
    assertThat(rect.height).isWithin(tolerance).of(360f)
  }

  // --- centeredTransform ---

  @Test
  fun centeredTransform_centersBitmapInBounds() {
    val transform = StickerGeometry.centeredTransform(100, 50, Size(400f, 300f))

    assertThat(transform.offset.x).isWithin(tolerance).of(150f)
    assertThat(transform.offset.y).isWithin(tolerance).of(125f)
    assertThat(transform.scale).isWithin(tolerance).of(1f)
  }

  // --- applyGesture ---

  @Test
  fun applyGesture_panOnly_movesOffset() {
    val start = StickerGeometry.centeredTransform(100, 100, Size(400f, 300f))

    val result =
      StickerGeometry.applyGesture(
        current = start,
        centroid = Offset(200f, 150f),
        pan = Offset(10f, -5f),
        zoom = 1f,
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    assertThat(result.offset.x).isWithin(tolerance).of(start.offset.x + 10f)
    assertThat(result.offset.y).isWithin(tolerance).of(start.offset.y - 5f)
    assertThat(result.scale).isWithin(tolerance).of(1f)
  }

  @Test
  fun applyGesture_zoomAboutBitmapCenter_keepsCenterFixed() {
    val start = StickerGeometry.centeredTransform(100, 100, Size(400f, 300f))
    val bitmapCenter = start.offset + Offset(50f, 50f)

    val result =
      StickerGeometry.applyGesture(
        current = start,
        centroid = bitmapCenter,
        pan = Offset.Zero,
        zoom = 1.5f,
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    val resultCenter = result.offset + Offset(50f, 50f)
    assertThat(resultCenter.x).isWithin(tolerance).of(bitmapCenter.x)
    assertThat(resultCenter.y).isWithin(tolerance).of(bitmapCenter.y)
    assertThat(result.scale).isWithin(tolerance).of(1.5f)
  }

  @Test
  fun applyGesture_zoomAboutOffCenterCentroid_movesCenterAwayFromCentroid() {
    // Regression test for the centroid-ignoring pinch bug: zooming in with the centroid left of
    // the bitmap center must push the center to the right, along the centroid->center ray.
    val start = StickerGeometry.centeredTransform(100, 100, Size(400f, 300f))
    val startCenter = start.offset + Offset(50f, 50f) // (200, 150)
    val centroid = Offset(startCenter.x - 40f, startCenter.y) // 40px left of center

    val result =
      StickerGeometry.applyGesture(
        current = start,
        centroid = centroid,
        pan = Offset.Zero,
        zoom = 1.5f,
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    val resultCenter = result.offset + Offset(50f, 50f)
    // centroid + (center - centroid) * 1.5 = 160 + 40 * 1.5 = 220.
    assertThat(resultCenter.x).isWithin(tolerance).of(220f)
    assertThat(resultCenter.y).isWithin(tolerance).of(150f)
  }

  @Test
  fun applyGesture_zoomOut_clampsAtMinScale() {
    val start = StickerGeometry.centeredTransform(100, 100, Size(400f, 300f))

    val result =
      StickerGeometry.applyGesture(
        current = start,
        centroid = Offset(200f, 150f),
        pan = Offset.Zero,
        zoom = 0.001f,
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    assertThat(result.scale).isWithin(tolerance).of(StickerGeometry.MIN_SCALE)
  }

  @Test
  fun applyGesture_zoomIn_clampsAtBoundsFitScale() {
    // 100x100 bitmap in 400x300 bounds: the largest fitting scale is 3 (height-limited).
    val start = StickerGeometry.centeredTransform(100, 100, Size(400f, 300f))

    val result =
      StickerGeometry.applyGesture(
        current = start,
        centroid = Offset(200f, 150f),
        pan = Offset.Zero,
        zoom = 100f,
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    assertThat(result.scale).isWithin(tolerance).of(3f)
  }

  @Test
  fun applyGesture_clampedZoom_stillAppliesPan() {
    // Regression test for the two-separate-updates bug: when the zoom clamps, the pan from the
    // same gesture event must still be applied.
    val start =
      StickerGeometry.applyGesture(
        current = StickerGeometry.centeredTransform(100, 100, Size(400f, 300f)),
        centroid = Offset(200f, 150f),
        pan = Offset.Zero,
        zoom = 100f, // scale now clamped at 3 (bounds-fit)
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    val result =
      StickerGeometry.applyGesture(
        current = start,
        centroid = Offset(200f, 150f),
        pan = Offset(25f, 0f),
        zoom = 100f, // still clamped, no scale change
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    assertThat(result.scale).isWithin(tolerance).of(3f)
    val resultCenter = result.offset + Offset(50f, 50f)
    // At scale 3 the 100px-wide bitmap spans 300px in 400px bounds: center is clamped to
    // [150, 250]. Starting centered (200) and panning +25 must land at 225.
    assertThat(resultCenter.x).isWithin(tolerance).of(225f)
  }

  @Test
  fun applyGesture_panPastEdge_clampsScaledStickerInsideBounds() {
    val start = StickerGeometry.centeredTransform(100, 100, Size(400f, 300f))

    val result =
      StickerGeometry.applyGesture(
        current = start,
        centroid = Offset(200f, 150f),
        pan = Offset(10_000f, 10_000f),
        zoom = 2f,
        bitmapWidth = 100,
        bitmapHeight = 100,
        bounds = Size(400f, 300f),
      )

    val resultCenter = result.offset + Offset(50f, 50f)
    // At scale 2, half-extent is 100: max center is (300, 200).
    assertThat(resultCenter.x).isWithin(tolerance).of(300f)
    assertThat(resultCenter.y).isWithin(tolerance).of(200f)
  }

  @Test
  fun applyGesture_bitmapLargerThanBounds_scaleClampsToBoundsFit() {
    val result =
      StickerGeometry.applyGesture(
        current = StickerTransform(),
        centroid = Offset.Zero,
        pan = Offset(50f, 50f),
        zoom = 1f,
        bitmapWidth = 1000,
        bitmapHeight = 1000,
        bounds = Size(400f, 300f),
      )

    // maxScale = min(5, 400/1000, 300/1000) = 0.3, so the oversized bitmap is scaled to fit.
    assertThat(result.scale).isWithin(tolerance).of(0.3f)
  }

  @Test
  fun applyGesture_stickerCannotFitAxis_centersOnThatAxisInsteadOfThrowing() {
    // 10000px bitmap: even at MIN_SCALE its extent (1000px) exceeds the bounds, so the
    // center clamp range is empty; the sticker must center on that axis rather than crash.
    val result =
      StickerGeometry.applyGesture(
        current = StickerTransform(),
        centroid = Offset.Zero,
        pan = Offset(50f, 50f),
        zoom = 0.001f,
        bitmapWidth = 10_000,
        bitmapHeight = 10_000,
        bounds = Size(400f, 300f),
      )

    assertThat(result.scale).isWithin(tolerance).of(StickerGeometry.MIN_SCALE)
    val resultCenter = result.offset + Offset(5_000f, 5_000f)
    assertThat(resultCenter.x).isWithin(tolerance).of(200f)
    assertThat(resultCenter.y).isWithin(tolerance).of(150f)
  }

  // --- toOverlayPlacement ---

  @Test
  fun toOverlayPlacement_centeredSticker_mapsToOriginAnchors() {
    val bounds = Size(400f, 300f)
    val transform = StickerGeometry.centeredTransform(100, 100, bounds)

    val placement = StickerGeometry.toOverlayPlacement(transform, 100, 100, bounds, 0)

    assertThat(placement.anchorX).isWithin(tolerance).of(0f)
    assertThat(placement.anchorY).isWithin(tolerance).of(0f)
  }

  @Test
  fun toOverlayPlacement_topLeftSticker_mapsToNegativeXPositiveY() {
    val bounds = Size(400f, 300f)
    val transform = StickerTransform(offset = Offset.Zero, scale = 1f)

    val placement = StickerGeometry.toOverlayPlacement(transform, 100, 100, bounds, 0)

    // Center at (50, 50): x -> -1 + 2*50/400 = -0.75; y -> 1 - 2*50/300 = 0.6667.
    assertThat(placement.anchorX).isWithin(tolerance).of(-0.75f)
    assertThat(placement.anchorY).isWithin(tolerance).of(2f / 3f)
  }

  @Test
  fun toOverlayPlacement_knownVideoWidth_compensatesScaleForWysiwyg() {
    val bounds = Size(960f, 540f)
    val transform = StickerTransform(offset = Offset.Zero, scale = 1.5f)

    val placement = StickerGeometry.toOverlayPlacement(transform, 100, 100, bounds, 1920)

    // uiScale * videoPixelWidth / boundsWidth = 1.5 * 1920 / 960 = 3.
    assertThat(placement.scale).isWithin(tolerance).of(3f)
  }

  @Test
  fun toOverlayPlacement_unknownVideoWidth_fallsBackToUiScale() {
    val bounds = Size(960f, 540f)
    val transform = StickerTransform(offset = Offset.Zero, scale = 1.5f)

    val placement = StickerGeometry.toOverlayPlacement(transform, 100, 100, bounds, 0)

    assertThat(placement.scale).isWithin(tolerance).of(1.5f)
  }
}
