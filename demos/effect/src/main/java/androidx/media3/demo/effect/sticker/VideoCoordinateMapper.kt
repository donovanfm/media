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

/**
 * Maps touch positions on an aspect-fit video view to normalized video coordinates.
 *
 * The sticker creation screen renders video fit-center inside its view, so a touch may land on
 * the letterbox/pillarbox bars rather than the video. MediaPipe's segmenter expects a normalized
 * [0, 1] point in video space; this does that conversion and rejects taps on the bars. Pure math,
 * unit-testable on the JVM.
 */
internal object VideoCoordinateMapper {

  /** A point in normalized video coordinates; both values are in [0, 1]. */
  data class NormalizedPoint(val x: Float, val y: Float)

  /**
   * Converts a tap at ([tapX], [tapY]) in view coordinates to normalized video coordinates for a
   * fit-center video of [videoWidth] x [videoHeight] display pixels shown in a view of
   * [viewWidth] x [viewHeight]. Returns null when the tap lands outside the displayed video or
   * when any dimension is unknown.
   */
  fun viewToNormalizedVideo(
    viewWidth: Float,
    viewHeight: Float,
    videoWidth: Int,
    videoHeight: Int,
    tapX: Float,
    tapY: Float,
  ): NormalizedPoint? {
    if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
      return null
    }
    val scale = minOf(viewWidth / videoWidth, viewHeight / videoHeight)
    val contentWidth = videoWidth * scale
    val contentHeight = videoHeight * scale
    val contentLeft = (viewWidth - contentWidth) / 2f
    val contentTop = (viewHeight - contentHeight) / 2f
    val x = (tapX - contentLeft) / contentWidth
    val y = (tapY - contentTop) / contentHeight
    return if (x in 0f..1f && y in 0f..1f) NormalizedPoint(x, y) else null
  }
}
