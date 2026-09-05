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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Typed contract for launching [StickerCreationActivity].
 *
 * Input: the video to cut a sticker from, or null to let the user pick one on the creation
 * screen. Output: the saved sticker's [StickerAsset.id], or null when the user cancelled.
 */
internal class CreateStickerContract : ActivityResultContract<Uri?, String?>() {

  override fun createIntent(context: Context, input: Uri?): Intent =
    Intent(context, StickerCreationActivity::class.java).apply {
      input?.let { putExtra(EXTRA_MEDIA_URI, it.toString()) }
    }

  override fun parseResult(resultCode: Int, intent: Intent?): String? =
    if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_STICKER_ID) else null

  companion object {
    const val EXTRA_MEDIA_URI = "androidx.media3.demo.effect.sticker.MEDIA_URI"
    const val EXTRA_STICKER_ID = "androidx.media3.demo.effect.sticker.STICKER_ID"
  }
}
