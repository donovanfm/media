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

import java.io.File

/**
 * A sticker image available for overlay placement.
 *
 * Stickers come from two sources: images bundled in the demo's assets, and custom stickers the
 * user cut out of a video with the creation screen, persisted in app-private storage by
 * [StickerRepository].
 */
internal sealed interface StickerAsset {
  /** Stable identity, unique across all assets. */
  val id: String

  /** Display name shown in the sticker picker. */
  val name: String

  /** An image bundled with the demo under `assets/`. */
  data class Bundled(override val name: String, val assetPath: String) : StickerAsset {
    override val id: String
      get() = assetPath
  }

  /** A user-created static sticker: a PNG cutout in app-private storage. */
  data class Static(override val id: String, override val name: String, val imageFile: File) :
    StickerAsset
}
