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
import android.graphics.BitmapFactory
import android.util.JsonReader
import android.util.JsonWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistence for user-created stickers.
 *
 * Stickers live in app-private storage (`filesDir/stickers/`), never in MediaStore: a demo
 * shouldn't pollute the device gallery, and app-private files work identically across all
 * supported API levels. Each static sticker is a PNG (lossless, alpha-capable) plus a small JSON
 * sidecar holding the user-visible name.
 *
 * All methods are suspending and do their IO on [ioDispatcher].
 */
internal class StickerRepository(
  context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  private val staticDir = File(context.filesDir, "stickers/static")

  /** Saves [sticker] as a new static sticker named [name] and returns its asset. */
  suspend fun saveStatic(sticker: Bitmap, name: String): StickerAsset.Static {
    return withContext(ioDispatcher) {
      staticDir.mkdirs()
      val id = newStickerId()
      val imageFile = File(staticDir, "$id.png")
      try {
        imageFile.outputStream().use { output ->
          if (!sticker.compress(Bitmap.CompressFormat.PNG, /* quality= */ 100, output)) {
            throw IOException("PNG compression failed")
          }
        }
        writeMetadata(File(staticDir, "$id.json"), name)
      } catch (e: Exception) {
        imageFile.delete()
        File(staticDir, "$id.json").delete()
        throw e
      }
      StickerAsset.Static(id, name, imageFile)
    }
  }

  /** Loads all persisted stickers, newest first. Unreadable entries are skipped. */
  suspend fun loadAll(): List<StickerAsset> {
    return withContext(ioDispatcher) {
      val imageFiles = staticDir.listFiles { file -> file.extension == "png" } ?: emptyArray()
      imageFiles
        .sortedByDescending { it.name }
        .mapNotNull { imageFile ->
          val id = imageFile.nameWithoutExtension
          val name = readMetadataName(File(staticDir, "$id.json")) ?: return@mapNotNull null
          StickerAsset.Static(id, name, imageFile)
        }
    }
  }

  /** Decodes the bitmap of a static sticker. Throws [IOException] when it can't be decoded. */
  suspend fun loadBitmap(asset: StickerAsset.Static): Bitmap {
    return withContext(ioDispatcher) {
      BitmapFactory.decodeFile(asset.imageFile.path)
        ?: throw IOException("Could not decode ${asset.imageFile.path}")
    }
  }

  /** Deletes a persisted sticker. Bundled assets can't be deleted. */
  suspend fun delete(asset: StickerAsset.Static) {
    withContext(ioDispatcher) {
      asset.imageFile.delete()
      File(staticDir, "${asset.id}.json").delete()
    }
  }

  private fun writeMetadata(file: File, name: String) {
    JsonWriter(file.writer()).use { writer ->
      writer.beginObject()
      writer.name("version").value(METADATA_VERSION)
      writer.name("name").value(name)
      writer.endObject()
    }
  }

  private fun readMetadataName(file: File): String? {
    if (!file.exists()) {
      return null
    }
    return try {
      var name: String? = null
      JsonReader(file.reader()).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
          when (reader.nextName()) {
            "name" -> name = reader.nextString()
            else -> reader.skipValue()
          }
        }
        reader.endObject()
      }
      name
    } catch (e: IOException) {
      null
    }
  }

  private fun newStickerId(): String =
    "sticker_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

  private companion object {
    const val METADATA_VERSION = 1L
  }
}
