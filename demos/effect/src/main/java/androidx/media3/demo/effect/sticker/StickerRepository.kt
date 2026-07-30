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
import android.os.Build
import android.util.JsonReader
import android.util.JsonWriter
import androidx.core.graphics.createBitmap
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
 * sidecar holding the user-visible name. Each animated sticker is a directory of WebP frames
 * (alpha-capable and much smaller than PNG across dozens of frames) plus a `manifest.json` with
 * the frame timeline — Android has no framework encoder for single-file animated WebP, so frames
 * plus a manifest is the pragmatic format.
 *
 * All methods are suspending and do their IO on [ioDispatcher].
 */
internal class StickerRepository(
  context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  private val staticDir = File(context.filesDir, "stickers/static")
  private val animatedDir = File(context.filesDir, "stickers/animated")

  /**
   * Saves [sticker] as a new static sticker named [name] and returns its asset. The sticker is
   * trimmed to its non-transparent bounds first so its visible content can be placed flush
   * against the video edges.
   */
  suspend fun saveStatic(sticker: Bitmap, name: String): StickerAsset.Static {
    return withContext(ioDispatcher) {
      val trimmed = sticker.trimmedToOpaqueBounds()
      staticDir.mkdirs()
      val id = newStickerId()
      val imageFile = File(staticDir, "$id.png")
      try {
        imageFile.outputStream().use { output ->
          if (!trimmed.compress(Bitmap.CompressFormat.PNG, /* quality= */ 100, output)) {
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

  /**
   * Saves a composed animation as a new animated sticker named [name]: one WebP per frame plus a
   * manifest, written into a temp directory and renamed into place so partial writes never
   * surface as broken stickers. The animation is trimmed to the union of its frames'
   * non-transparent bounds first (a uniform crop, so frame size stays constant).
   */
  suspend fun saveAnimated(
    animation: StickerFrameRecorder.ComposedAnimation,
    name: String,
  ): StickerAsset.Animated {
    @Suppress("NAME_SHADOWING") val animation = animation.trimmedToOpaqueBounds()
    return withContext(ioDispatcher) {
      animatedDir.mkdirs()
      val id = newStickerId()
      val stagingDir = File(animatedDir, "$id.tmp")
      val finalDir = File(animatedDir, id)
      try {
        stagingDir.mkdirs()
        val frameBitmap = createBitmap(animation.width, animation.height)
        for (i in animation.frames.indices) {
          frameBitmap.setPixels(
            animation.frames[i],
            /* offset= */ 0,
            /* stride= */ animation.width,
            /* x= */ 0,
            /* y= */ 0,
            animation.width,
            animation.height,
          )
          File(stagingDir, frameFileName(i)).outputStream().use { output ->
            if (!frameBitmap.compress(webpFormat(), WEBP_QUALITY, output)) {
              throw IOException("WebP compression failed for frame $i")
            }
          }
        }
        writeAnimatedManifest(File(stagingDir, MANIFEST_FILE_NAME), name, animation)
        if (!stagingDir.renameTo(finalDir)) {
          throw IOException("Could not move $stagingDir into place")
        }
      } catch (e: Exception) {
        stagingDir.deleteRecursively()
        throw e
      }
      StickerAsset.Animated(
        id = id,
        name = name,
        directory = finalDir,
        frameCount = animation.frames.size,
        durationUs = animation.durationUs,
        firstFrameFile = File(finalDir, frameFileName(0)),
      )
    }
  }

  /** Loads all persisted stickers, newest first. Unreadable entries are skipped. */
  suspend fun loadAll(): List<StickerAsset> {
    return withContext(ioDispatcher) {
      val staticAssets =
        (staticDir.listFiles { file -> file.extension == "png" } ?: emptyArray()).mapNotNull {
          imageFile ->
          val id = imageFile.nameWithoutExtension
          val name = readMetadataName(File(staticDir, "$id.json")) ?: return@mapNotNull null
          StickerAsset.Static(id, name, imageFile)
        }
      val animatedAssets =
        (animatedDir.listFiles { file -> file.isDirectory && file.extension != "tmp" }
            ?: emptyArray())
          .mapNotNull { directory -> readAnimatedManifest(directory) }
      (staticAssets + animatedAssets).sortedByDescending { it.id }
    }
  }

  /** Decodes the bitmap of a static sticker. Throws [IOException] when it can't be decoded. */
  suspend fun loadBitmap(asset: StickerAsset.Static): Bitmap {
    return withContext(ioDispatcher) { decodeFile(asset.imageFile) }
  }

  /** Decodes the first frame of an animated sticker for previews and placement. */
  suspend fun loadFirstFrame(asset: StickerAsset.Animated): Bitmap {
    return withContext(ioDispatcher) { decodeFile(asset.firstFrameFile) }
  }

  /** Loads all frames of an animated sticker. Throws [IOException] on unreadable data. */
  suspend fun loadAnimated(asset: StickerAsset.Animated): AnimatedSticker {
    return withContext(ioDispatcher) {
      val manifest =
        readAnimatedManifestTimeline(File(asset.directory, MANIFEST_FILE_NAME))
          ?: throw IOException("Missing or invalid manifest in ${asset.directory}")
      val frames = manifest.frameFiles.map { decodeFile(File(asset.directory, it)) }
      AnimatedSticker(frames, manifest.timestampsUs, manifest.durationUs)
    }
  }

  /** Deletes a persisted sticker. Bundled assets are ignored. */
  suspend fun delete(asset: StickerAsset) {
    withContext(ioDispatcher) {
      when (asset) {
        is StickerAsset.Bundled -> {}
        is StickerAsset.Static -> {
          asset.imageFile.delete()
          File(staticDir, "${asset.id}.json").delete()
        }
        is StickerAsset.Animated -> asset.directory.deleteRecursively()
      }
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

  private class AnimatedManifestTimeline(
    val frameFiles: List<String>,
    val timestampsUs: LongArray,
    val durationUs: Long,
  )

  private fun writeAnimatedManifest(
    file: File,
    name: String,
    animation: StickerFrameRecorder.ComposedAnimation,
  ) {
    JsonWriter(file.writer()).use { writer ->
      writer.beginObject()
      writer.name("version").value(METADATA_VERSION)
      writer.name("name").value(name)
      writer.name("width").value(animation.width.toLong())
      writer.name("height").value(animation.height.toLong())
      writer.name("durationUs").value(animation.durationUs)
      writer.name("frames")
      writer.beginArray()
      for (i in animation.frames.indices) {
        writer.beginObject()
        writer.name("file").value(frameFileName(i))
        writer.name("timestampUs").value(animation.timestampsUs[i])
        writer.endObject()
      }
      writer.endArray()
      writer.endObject()
    }
  }

  /** Builds the [StickerAsset.Animated] for a sticker directory, or null when unreadable. */
  private fun readAnimatedManifest(directory: File): StickerAsset.Animated? {
    val manifestFile = File(directory, MANIFEST_FILE_NAME)
    var name: String? = null
    var durationUs = 0L
    var frameCount = 0
    var firstFrameFile: String? = null
    try {
      if (!manifestFile.exists()) {
        return null
      }
      JsonReader(manifestFile.reader()).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
          when (reader.nextName()) {
            "name" -> name = reader.nextString()
            "durationUs" -> durationUs = reader.nextLong()
            "frames" -> {
              reader.beginArray()
              while (reader.hasNext()) {
                reader.beginObject()
                while (reader.hasNext()) {
                  when (reader.nextName()) {
                    "file" -> {
                      val fileName = reader.nextString()
                      if (firstFrameFile == null) {
                        firstFrameFile = fileName
                      }
                    }
                    else -> reader.skipValue()
                  }
                }
                reader.endObject()
                frameCount++
              }
              reader.endArray()
            }
            else -> reader.skipValue()
          }
        }
        reader.endObject()
      }
    } catch (e: IOException) {
      return null
    } catch (e: IllegalStateException) {
      return null
    }
    val stickerName = name ?: return null
    val frameFile = firstFrameFile ?: return null
    if (frameCount == 0 || durationUs <= 0) {
      return null
    }
    return StickerAsset.Animated(
      id = directory.name,
      name = stickerName,
      directory = directory,
      frameCount = frameCount,
      durationUs = durationUs,
      firstFrameFile = File(directory, frameFile),
    )
  }

  /** Reads the full frame timeline of a manifest, or null when unreadable. */
  private fun readAnimatedManifestTimeline(manifestFile: File): AnimatedManifestTimeline? {
    val frameFiles = mutableListOf<String>()
    val timestamps = mutableListOf<Long>()
    var durationUs = 0L
    try {
      JsonReader(manifestFile.reader()).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
          when (reader.nextName()) {
            "durationUs" -> durationUs = reader.nextLong()
            "frames" -> {
              reader.beginArray()
              while (reader.hasNext()) {
                reader.beginObject()
                var file: String? = null
                var timestampUs: Long? = null
                while (reader.hasNext()) {
                  when (reader.nextName()) {
                    "file" -> file = reader.nextString()
                    "timestampUs" -> timestampUs = reader.nextLong()
                    else -> reader.skipValue()
                  }
                }
                reader.endObject()
                if (file == null || timestampUs == null) {
                  return null
                }
                frameFiles += file
                timestamps += timestampUs
              }
              reader.endArray()
            }
            else -> reader.skipValue()
          }
        }
        reader.endObject()
      }
    } catch (e: IOException) {
      return null
    } catch (e: IllegalStateException) {
      return null
    }
    if (frameFiles.isEmpty() || durationUs <= 0) {
      return null
    }
    return AnimatedManifestTimeline(frameFiles, timestamps.toLongArray(), durationUs)
  }

  private fun decodeFile(file: File): Bitmap =
    BitmapFactory.decodeFile(file.path) ?: throw IOException("Could not decode ${file.path}")

  private fun frameFileName(index: Int): String = "frame_%03d.webp".format(Locale.US, index)

  @Suppress("DEPRECATION")
  private fun webpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= 30) {
      Bitmap.CompressFormat.WEBP_LOSSY
    } else {
      Bitmap.CompressFormat.WEBP
    }

  private fun newStickerId(): String =
    "sticker_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

  private companion object {
    const val METADATA_VERSION = 1L
    const val MANIFEST_FILE_NAME = "manifest.json"
    const val WEBP_QUALITY = 90
  }
}
