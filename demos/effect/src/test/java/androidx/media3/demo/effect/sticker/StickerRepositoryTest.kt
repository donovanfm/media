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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for [StickerRepository]. */
@RunWith(RobolectricTestRunner::class)
class StickerRepositoryTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val repository = StickerRepository(context, ioDispatcher = Dispatchers.Unconfined)

  private fun testBitmap(): Bitmap =
    Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

  @Test
  fun saveStatic_thenLoadAll_roundTrips() = runTest {
    val saved = repository.saveStatic(testBitmap(), "My sticker")

    val assets = repository.loadAll()

    assertThat(assets).hasSize(1)
    val loaded = assets[0] as StickerAsset.Static
    assertThat(loaded.id).isEqualTo(saved.id)
    assertThat(loaded.name).isEqualTo("My sticker")
    assertThat(loaded.imageFile.exists()).isTrue()
  }

  @Test
  fun loadBitmap_returnsDecodedPixels() = runTest {
    val saved = repository.saveStatic(testBitmap(), "Red square")

    val bitmap = repository.loadBitmap(saved)

    assertThat(bitmap.width).isEqualTo(4)
    assertThat(bitmap.height).isEqualTo(4)
    assertThat(bitmap.getPixel(2, 2)).isEqualTo(Color.RED)
  }

  @Test
  fun delete_removesImageAndMetadata() = runTest {
    val saved = repository.saveStatic(testBitmap(), "Doomed")

    repository.delete(saved)

    assertThat(repository.loadAll()).isEmpty()
    assertThat(saved.imageFile.exists()).isFalse()
  }

  @Test
  fun loadAll_skipsEntriesWithoutMetadata() = runTest {
    repository.saveStatic(testBitmap(), "Valid")
    // An orphaned image without its JSON sidecar must be skipped, not crash loading.
    val staticDir = File(context.filesDir, "stickers/static")
    File(staticDir, "sticker_orphan.png").writeBytes(byteArrayOf(1, 2, 3))

    val assets = repository.loadAll()

    assertThat(assets).hasSize(1)
    assertThat(assets[0].name).isEqualTo("Valid")
  }
}
