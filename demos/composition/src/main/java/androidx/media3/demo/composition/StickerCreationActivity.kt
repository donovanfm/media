/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.composition

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.set
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.RegionOfInterest
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StickerCreationActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      CompositionDemoTheme {
        StickerCreationScreen()
      }
    }
  }
}

@Composable
fun StickerCreationScreen() {
  var videoUri by remember { mutableStateOf<Uri?>(null) }
  val context = LocalContext.current
  val exoPlayer = remember { ExoPlayer.Builder(context).build() }
  var segmenter by remember { mutableStateOf<InteractiveSegmenter?>(null) }
  var capturedFrame by remember { mutableStateOf<Bitmap?>(null) }
  var segmentationMask by remember { mutableStateOf<Bitmap?>(null) }
  var isSegmenting by remember { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val lifecycleOwner = LocalLifecycleOwner.current

  val videoPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.GetContent(),
      onResult = { uri: Uri? ->
        videoUri = uri
        uri?.let {
          val mediaItem = MediaItem.fromUri(it)
          exoPlayer.setMediaItem(mediaItem)
          exoPlayer.prepare()
          exoPlayer.playWhenReady = true
        }
      },
    )

  LaunchedEffect(lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      withContext(Dispatchers.IO) {
        val options =
          InteractiveSegmenterOptions.builder()
            .setBaseOptions(
              BaseOptions.builder()
                .setModelAssetPath("magic_touch.tflite")
////                .setDelegate(BaseOptions.Delegate.GPU)
                .build()
            )
            .setOutputConfidenceMasks(true)
              .setOutputCategoryMask(true)
            .build()
        segmenter = InteractiveSegmenter.createFromOptions(context, options)
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      exoPlayer.release()
      segmenter?.close()
    }
  }

  Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (videoUri == null) {
        Button(onClick = { videoPickerLauncher.launch("video/*") }) { Text("Select Video") }
      } else {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
          Box {
            AndroidView(
              factory = { ctx ->
                val inflatedView = LayoutInflater.from(ctx).inflate(R.layout.sticker_player_view, null, false)
                val pv = inflatedView.findViewById<PlayerView>(R.id.player_view)
                pv.apply {
                  player = exoPlayer
                  useController = false
                  (videoSurfaceView as? TextureView)?.isOpaque = false
                }
                playerViewRef = pv
                pv
              },
              modifier =
              Modifier
                .fillMaxWidth()
                .height(300.dp)
                .pointerInput(segmenter, playerViewRef) {
                  detectTapGestures(
                    onLongPress = { offset ->
                      val playerView = playerViewRef ?: return@detectTapGestures
                      if (segmenter == null) return@detectTapGestures
                      exoPlayer.pause()
                      isSegmenting = true

                      val frame = (playerView.videoSurfaceView as? TextureView)?.bitmap
                      if (frame == null) {
                        CoroutineScope(Dispatchers.Main).launch {
                          snackbarHostState.showSnackbar(
                            "Frame capture not supported on this device."
                          )
                        }
                        isSegmenting = false
                        return@detectTapGestures
                      }
                      capturedFrame = frame

                      val viewWidth = size.width
                      val viewHeight = size.height
                      val frameWidth = frame.width
                      val frameHeight = frame.height

                      val viewAspectRatio = viewWidth.toFloat() / viewHeight
                      val frameAspectRatio = frameWidth.toFloat() / frameHeight

                      val scaledWidth: Float
                      val scaledHeight: Float
                      if (viewAspectRatio > frameAspectRatio) {
                        scaledHeight = viewHeight.toFloat()
                        scaledWidth = scaledHeight * frameAspectRatio
                      } else {
                        scaledWidth = viewWidth.toFloat()
                        scaledHeight = scaledWidth / frameAspectRatio
                      }

                      val offsetX = (viewWidth - scaledWidth) / 2f
                      val offsetY = (viewHeight - scaledHeight) / 2f

                      val adjustedX = offset.x - offsetX
                      val adjustedY = offset.y - offsetY

                      if (adjustedX < 0 || adjustedX > scaledWidth || adjustedY < 0 || adjustedY > scaledHeight) {
                        CoroutineScope(Dispatchers.Main).launch {
                          snackbarHostState.showSnackbar(
                            "Please press on the video, not the black bars."
                          )
                        }
                        isSegmenting = false
                        return@detectTapGestures
                      }

                      val normalizedX = adjustedX / scaledWidth
                      val normalizedY = adjustedY / scaledHeight

                      CoroutineScope(Dispatchers.Default).launch {
                        val mpImage = BitmapImageBuilder(frame).build()
                        val roi =
                          RegionOfInterest.create(
                            NormalizedKeypoint.create(
                              normalizedX,
                              normalizedY
                            )
                          )
                        val result = segmenter?.segment(mpImage, roi)
                        val confidenceMasks = result?.confidenceMasks()?.get()
                        val byteBuffer = if (confidenceMasks != null && confidenceMasks.isNotEmpty()) {
                          ByteBufferExtractor.extract(confidenceMasks[0])
                        } else {
                          null
                        }

                        if (byteBuffer != null) {
                          segmentationMask =
                            generateMaskBitmap(
                              byteBuffer,
                              frame.width,
                              frame.height,
                              (normalizedX * frame.width).toInt(),
                              (normalizedY * frame.height).toInt()
                            )
                        }
                        isSegmenting = false
                      }
                    }
                  )
                },
            )
            if (isSegmenting) {
              CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            segmentationMask?.let {
              Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Segmentation Mask",
                modifier = Modifier.matchParentSize(),
              )
            }
          }
          Button(
            onClick = {
              val frame = capturedFrame
              val mask = segmentationMask
              if (frame != null && mask != null) {
                val sticker = createSticker(frame, mask)
                val uri = saveBitmapToMediaStore(context, sticker)
                if (uri != null) {
                  val resultIntent = Intent().apply { data = uri }
                  (context as? Activity)?.setResult(Activity.RESULT_OK, resultIntent)
                  (context as? Activity)?.finish()
                } else {
                  // Handle save failure
                  Log.e("StickerCreation", "Failed to save sticker to MediaStore")
                }
              }
            },
            modifier = Modifier.padding(top = 16.dp),
            enabled = capturedFrame != null && segmentationMask != null && !isSegmenting,
          ) {
            Text("Save Sticker")
          }
        }
      }
    }
  }
}

private fun generateMaskBitmap(byteBuffer: ByteBuffer, width: Int, height: Int, startX: Int, startY: Int, confidenceThreshold: Float = 0.001f): Bitmap {
  byteBuffer.rewind()
  byteBuffer.order(ByteOrder.nativeOrder())
  val floatBuffer = byteBuffer.asFloatBuffer()

  val pixels = IntArray(width * height)
  val visited = BooleanArray(width * height)
  val queue = java.util.ArrayDeque<Int>()

  val startIdx = (startY * width + startX).coerceIn(0, width * height - 1)

  if (floatBuffer.get(startIdx) > confidenceThreshold) {
    queue.add(startIdx)
    visited[startIdx] = true
  }

  while (!queue.isEmpty()) {
    val currIdx = queue.poll()
    
    // Mark pixel
    pixels[currIdx] = Color.argb(150, 0, 0, 255)

    val currX = currIdx % width
    val currY = currIdx / width

    // Neighbors: up, down, left, right
    val neighbors = intArrayOf(
      currIdx - 1,
      currIdx + 1,
      currIdx - width,
      currIdx + width
    )

    for (neighborIdx in neighbors) {
      if (neighborIdx < 0 || neighborIdx >= width * height) continue

      // Wrap-around checks
      if (neighborIdx == currIdx - 1 && currX == 0) continue
      if (neighborIdx == currIdx + 1 && currX == width - 1) continue

      if (!visited[neighborIdx]) {
        if (floatBuffer.get(neighborIdx) > confidenceThreshold) {
          visited[neighborIdx] = true
          queue.add(neighborIdx)
        }
      }
    }
  }

  return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun createSticker(frame: Bitmap, mask: Bitmap): Bitmap {
  val width = frame.width
  val height = frame.height
  val maskPixels = IntArray(width * height)
  mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

  var minX = width
  var minY = height
  var maxX = -1
  var maxY = -1

  for (i in maskPixels.indices) {
    if (maskPixels[i] != Color.TRANSPARENT) {
      val x = i % width
      val y = i / width
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
  }

  if (maxX < minX || maxY < minY) {
    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
  }

  val stickerWidth = maxX - minX + 1
  val stickerHeight = maxY - minY + 1
  val stickerPixels = IntArray(stickerWidth * stickerHeight)
  val framePixels = IntArray(width * height)
  frame.getPixels(framePixels, 0, width, 0, 0, width, height)

  for (y in 0 until stickerHeight) {
    for (x in 0 until stickerWidth) {
      val srcIndex = (minY + y) * width + (minX + x)
      if (maskPixels[srcIndex] != Color.TRANSPARENT) {
        stickerPixels[y * stickerWidth + x] = framePixels[srcIndex]
      }
    }
  }

  return Bitmap.createBitmap(stickerPixels, stickerWidth, stickerHeight, Bitmap.Config.ARGB_8888)
}

private fun saveBitmapToMediaStore(context: android.content.Context, bitmap: Bitmap): Uri? {
  val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
  val filename = "sticker_$timestamp.png"

  val contentValues = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Stickers")
  }

  val resolver = context.contentResolver
  var uri: Uri? = null

  try {
    uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    if (uri != null) {
      val outputStream: OutputStream? = resolver.openOutputStream(uri)
      outputStream?.use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      Log.d("StickerCreation", "Sticker saved to MediaStore: $uri")
    }
  } catch (e: Exception) {
    Log.e("StickerCreation", "Failed to save to MediaStore", e)
    if (uri != null) {
        resolver.delete(uri, null, null)
    }
    return null
  }
  return uri
}
