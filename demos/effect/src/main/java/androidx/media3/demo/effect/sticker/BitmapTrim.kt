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

/**
 * Returns this bitmap cropped to the tightest box containing all of its non-transparent pixels,
 * or the bitmap itself when there is nothing to trim (no transparent border, or no opaque pixels
 * at all).
 *
 * Sticker placement clamps the whole bitmap rect inside the video, so a transparent border stops
 * the visible content short of the video edges; trimming restores flush placement. Bundled assets
 * and previously saved stickers are trimmed at load, new stickers at save.
 */
internal fun Bitmap.trimmedToOpaqueBounds(): Bitmap {
  val pixels = IntArray(width * height)
  getPixels(pixels, 0, width, 0, 0, width, height)
  val bounds = SegmentationMaskProcessor.opaqueBounds(pixels, width, height) ?: return this
  if (bounds.width == width && bounds.height == height) {
    return this
  }
  return Bitmap.createBitmap(this, bounds.left, bounds.top, bounds.width, bounds.height)
}
