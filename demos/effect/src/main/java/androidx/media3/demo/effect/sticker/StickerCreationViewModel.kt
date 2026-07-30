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

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.effect.R
import androidx.media3.demo.effect.sticker.VideoCoordinateMapper.NormalizedPoint
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel for the sticker creation screen.
 *
 * Owns the screen's short-lived [ExoPlayer] (separate from the main screen's player so captured
 * frames are free of applied effects), the [SegmenterEngine], and the [StickerRepository]. All
 * work runs in [viewModelScope] with UI state written only from the main dispatcher; frames are
 * captured on the main thread through a UI-provided [FrameSource] so the ViewModel never touches
 * views.
 */
@OptIn(UnstableApi::class)
internal class StickerCreationViewModel(application: Application) : AndroidViewModel(application) {

  /**
   * Main-thread video frame source, provided by the UI while a video surface is attached.
   * Implementations copy the current frame into [reuse] when it is compatible, else allocate a
   * bitmap capped at [CAPTURE_MAX_DIMENSION] on the long edge. Returns null when no frame is
   * available.
   */
  fun interface FrameSource {
    fun captureFrame(reuse: Bitmap?): Bitmap?
  }

  private val _uiState = MutableStateFlow(StickerCreationUiState())
  val uiState: StateFlow<StickerCreationUiState> = _uiState.asStateFlow()

  /** Emits the saved sticker's id once saving completes; the Activity finishes with it. */
  private val _savedStickerId = MutableStateFlow<String?>(null)
  val savedStickerId: StateFlow<String?> = _savedStickerId.asStateFlow()

  val player: ExoPlayer by lazy {
    ExoPlayer.Builder(getApplication()).build().apply {
      playWhenReady = true
      repeatMode = Player.REPEAT_MODE_ONE
      addListener(
        object : Player.Listener {
          override fun onVideoSizeChanged(videoSize: VideoSize) {
            val aspectRatio =
              if (videoSize.width > 0 && videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
              } else {
                0f
              }
            _uiState.update {
              // A stale player event must not clobber a photo source's aspect ratio.
              if (it.sourceImage != null) it else it.copy(sourceAspectRatio = aspectRatio)
            }
          }

          override fun onPlayerError(error: PlaybackException) {
            _uiState.update {
              it.copy(errorMessage = getString(R.string.sticker_error_playback))
            }
          }
        }
      )
    }
  }

  private val segmenterEngine = SegmenterEngine(getApplication())
  private val repository = StickerRepository(getApplication())

  private var frameSource: FrameSource? = null
  private var pendingCutout: Bitmap? = null
  private var pendingAnimated: StickerFrameRecorder.ComposedAnimation? = null
  private var captureJob: Job? = null

  // The finger's latest position during animated recording; written by drag events on the main
  // thread, read by the capture loop each iteration. Null once the finger lifts, which lets the
  // loop drain naturally instead of cancelling mid-native-call.
  @Volatile private var latestPoint: NormalizedPoint? = null

  init {
    viewModelScope.launch {
      try {
        segmenterEngine.prepare()
        _uiState.update { it.copy(segmenterReady = true) }
      } catch (e: SegmenterEngine.SegmentationException) {
        _uiState.update {
          it.copy(errorMessage = getString(R.string.sticker_error_segmenter_init))
        }
      }
    }
  }

  /**
   * Sets the photo or video to cut stickers from, or moves to [CreationPhase.AwaitingSource] when
   * null. Images and videos are told apart by their content type; anything that isn't an image
   * (including http(s) URIs, whose type is unknown) is treated as video.
   */
  fun setSource(uri: Uri?) {
    if (uri == null) {
      _uiState.update { it.copy(phase = CreationPhase.AwaitingSource) }
      return
    }
    stopRecording()
    pendingCutout = null
    pendingAnimated = null
    viewModelScope.launch {
      val isImage =
        withContext(Dispatchers.IO) {
          getApplication<Application>().contentResolver.getType(uri)?.startsWith("image/") == true
        }
      if (isImage) {
        val image = withContext(Dispatchers.IO) { decodeScaledImage(uri) }
        if (image == null) {
          _uiState.update {
            it.copy(errorMessage = getString(R.string.sticker_error_source_load))
          }
          return@launch
        }
        player.stop()
        player.clearMediaItems()
        _uiState.update {
          it.copy(
            phase = CreationPhase.Playing,
            sourceImage = image,
            sourceAspectRatio = image.width.toFloat() / image.height,
            // Recording a still image would just repeat one frame.
            mode = StickerMode.STATIC,
          )
        }
      } else {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        _uiState.update {
          it.copy(phase = CreationPhase.Playing, sourceImage = null, sourceAspectRatio = 0f)
        }
      }
    }
  }

  /** Switches between static and animated capture. Ignored while a recording is in progress. */
  fun setMode(mode: StickerMode) {
    val currentState = _uiState.value
    val phase = currentState.phase
    if (phase is CreationPhase.Recording || phase == CreationPhase.Saving) {
      return
    }
    if (mode == StickerMode.ANIMATED && currentState.sourceImage != null) {
      return
    }
    pendingCutout = null
    pendingAnimated = null
    _uiState.update {
      it.copy(
        mode = mode,
        phase = if (it.phase == CreationPhase.AwaitingSource) it.phase else CreationPhase.Playing,
      )
    }
    if (phase != CreationPhase.AwaitingSource) {
      resumeSource()
    }
  }

  /** Attaches or detaches the UI's frame source; pass null when the surface goes away. */
  fun attachFrameSource(source: FrameSource?) {
    frameSource = source
  }

  /**
   * Cuts a static sticker: segments the object at [point] in the current source — a photo, or
   * the current video frame (pausing playback) — and shows the mask preview.
   */
  fun createStaticSticker(point: NormalizedPoint) {
    val currentState = _uiState.value
    if (currentState.phase != CreationPhase.Playing || !currentState.segmenterReady) {
      return
    }
    viewModelScope.launch {
      val frame =
        currentState.sourceImage
          ?: run {
            player.pause()
            frameSource?.captureFrame(null)
          }
      if (frame == null) {
        _uiState.update {
          it.copy(
            phase = CreationPhase.Playing,
            errorMessage = getString(R.string.sticker_error_frame_capture),
          )
        }
        resumeSource()
        return@launch
      }
      _uiState.update { it.copy(phase = CreationPhase.Segmenting) }
      try {
        val mask = segmenterEngine.segment(frame, point.x, point.y)
        val preview = withContext(Dispatchers.Default) { buildPreview(frame, mask) }
        if (preview == null) {
          _uiState.update {
            it.copy(
              phase = CreationPhase.Playing,
              errorMessage = getString(R.string.sticker_error_empty_mask),
            )
          }
          resumeSource()
        } else {
          pendingCutout = preview.second
          _uiState.update {
            it.copy(phase = CreationPhase.StaticPreview(preview.first, preview.second))
          }
        }
      } catch (e: SegmenterEngine.SegmentationException) {
        _uiState.update {
          it.copy(
            phase = CreationPhase.Playing,
            errorMessage = getString(R.string.sticker_error_segmentation),
          )
        }
        resumeSource()
      }
    }
  }

  /**
   * Starts recording an animated sticker at [point]. The video keeps playing; each iteration of
   * the capture loop grabs the current frame, segments at the finger's latest position, and
   * accumulates the cutout — inference latency is the frame pacing, so frames rendered while the
   * segmenter is busy are implicitly dropped and no frame is ever segmented stale. Recording ends
   * when the finger lifts ([stopRecording]), a capacity cap is hit, or an error occurs.
   */
  fun startRecording(point: NormalizedPoint) {
    val currentState = _uiState.value
    if (
      currentState.phase != CreationPhase.Playing ||
        !currentState.segmenterReady ||
        currentState.sourceImage != null
    ) {
      return
    }
    latestPoint = point
    captureJob =
      viewModelScope.launch {
        val recorder = StickerFrameRecorder()
        val startTimeNs = SystemClock.elapsedRealtimeNanos()
        var reusableFrame: Bitmap? = null
        var framePixels: IntArray? = null
        _uiState.update { it.copy(phase = CreationPhase.Recording(0)) }
        while (isActive && !recorder.isFull) {
          val fingerPoint = latestPoint ?: break
          val frame = frameSource?.captureFrame(reusableFrame) ?: break
          reusableFrame = frame
          val timestampUs = (SystemClock.elapsedRealtimeNanos() - startTimeNs) / 1000
          // A failed or timed-out segmentation ends the recording but keeps the frames captured
          // so far — better a short sticker than a discarded one. The timeout also protects the
          // UI from a wedged native call (segment() abandons the wait; the engine thread keeps
          // running the call in the background).
          val mask =
            try {
              withTimeoutOrNull(SEGMENT_TIMEOUT_MS) {
                segmenterEngine.segment(frame, fingerPoint.x, fingerPoint.y)
              }
            } catch (e: SegmenterEngine.SegmentationException) {
              null
            }
          if (mask == null) {
            Log.w(TAG, "Segmentation failed or timed out; ending recording early")
            break
          }
          if (mask.width != frame.width || mask.height != frame.height) {
            Log.w(TAG, "Mask ${mask.width}x${mask.height} != frame ${frame.width}x${frame.height}")
            break
          }
          val pixels =
            framePixels?.takeIf { it.size == frame.width * frame.height }
              ?: IntArray(frame.width * frame.height).also { framePixels = it }
          withContext(Dispatchers.Default) {
            val alphaMask =
              SegmentationMaskProcessor.toAlphaMask(mask.values, mask.width, mask.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            recorder.addFrame(pixels, frame.width, alphaMask, timestampUs)
          }
          _uiState.update { it.copy(phase = CreationPhase.Recording(recorder.frameCount)) }
        }
        player.pause()
        val composed = withContext(Dispatchers.Default) { recorder.composeFrames() }
        if (composed == null) {
          _uiState.update {
            it.copy(
              phase = CreationPhase.Playing,
              errorMessage = getString(R.string.sticker_error_empty_mask),
            )
          }
          player.play()
        } else {
          pendingAnimated = composed
          _uiState.update {
            it.copy(phase = CreationPhase.AnimatedPreview(composed.toAnimatedSticker()))
          }
        }
      }
  }

  /** Tracks the finger during recording so the segmentation point follows the object. */
  fun updateFingerPosition(point: NormalizedPoint) {
    if (_uiState.value.phase is CreationPhase.Recording) {
      latestPoint = point
    }
  }

  /** Ends the recording; the capture loop drains after any in-flight segmentation completes. */
  fun stopRecording() {
    latestPoint = null
  }

  /** Updates the name the sticker will be saved under. */
  fun setStickerName(name: String) {
    _uiState.update { it.copy(stickerName = name) }
  }

  /** Persists the pending cutout or animation and emits its id via [savedStickerId]. */
  fun saveSticker() {
    val cutout = pendingCutout
    val animation = pendingAnimated
    if (cutout == null && animation == null) {
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(phase = CreationPhase.Saving) }
      try {
        val name =
          _uiState.value.stickerName.ifBlank { getString(R.string.sticker_default_name) }
        val asset =
          if (animation != null) {
            repository.saveAnimated(animation, name)
          } else {
            repository.saveStatic(checkNotNull(cutout), name)
          }
        _savedStickerId.value = asset.id
      } catch (e: Exception) {
        if (e is IOException || e is RuntimeException) {
          _uiState.update {
            it.copy(
              phase = CreationPhase.Playing,
              errorMessage = getString(R.string.sticker_error_save),
            )
          }
          resumeSource()
        } else {
          throw e
        }
      }
    }
  }

  /** Discards the current preview and resumes the source. */
  fun discardPreview() {
    pendingCutout = null
    pendingAnimated = null
    _uiState.update { it.copy(phase = CreationPhase.Playing) }
    resumeSource()
  }

  /** Clears any active error message. */
  fun clearErrorMessage() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  /** Resumes playback when the source is a video; photos have nothing to resume. */
  private fun resumeSource() {
    if (_uiState.value.sourceImage == null) {
      player.play()
    }
  }

  /**
   * Decodes a photo capped at [CAPTURE_MAX_DIMENSION] on the long edge, or null on failure. Uses
   * ImageDecoder on API 28+ (which applies EXIF rotation and exact target sizing); the
   * BitmapFactory fallback on older API levels ignores EXIF orientation — acceptable for a demo.
   * The result must be a software bitmap so its pixels can be read for segmentation and cutouts.
   */
  private fun decodeScaledImage(uri: Uri): Bitmap? {
    val resolver = getApplication<Application>().contentResolver
    return try {
      if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          val longEdge = max(info.size.width, info.size.height)
          if (longEdge > CAPTURE_MAX_DIMENSION) {
            val scale = CAPTURE_MAX_DIMENSION.toFloat() / longEdge
            decoder.setTargetSize(
              (info.size.width * scale).roundToInt().coerceAtLeast(1),
              (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
          }
        }
      } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
          return null
        }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= CAPTURE_MAX_DIMENSION) {
          sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
      }
    } catch (e: IOException) {
      Log.w(TAG, "Could not decode image source", e)
      null
    }
  }

  override fun onCleared() {
    super.onCleared()
    captureJob?.cancel()
    player.release()
    segmenterEngine.close()
  }

  /** Converts a composed animation's pixel arrays into bitmaps for the preview UI. */
  private fun StickerFrameRecorder.ComposedAnimation.toAnimatedSticker(): AnimatedSticker {
    val bitmaps =
      frames.map { pixels ->
        createBitmap(width, height).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
      }
    return AnimatedSticker(bitmaps, timestampsUs, durationUs)
  }

  /**
   * Converts the confidence mask into (mask preview at frame size, cutout bitmap), or null when
   * the mask selected nothing. Runs on a background dispatcher.
   */
  private fun buildPreview(
    frame: Bitmap,
    mask: SegmenterEngine.ConfidenceMask,
  ): Pair<Bitmap, Bitmap>? {
    val alphaMask =
      SegmentationMaskProcessor.toAlphaMask(mask.values, mask.width, mask.height)
    if (alphaMask.bbox == null || mask.width != frame.width || mask.height != frame.height) {
      // A mask sized differently from the frame can't be cut out; treat it like an empty result.
      if (mask.width != frame.width || mask.height != frame.height) {
        return null
      }
    }
    val framePixels = IntArray(frame.width * frame.height)
    frame.getPixels(framePixels, 0, frame.width, 0, 0, frame.width, frame.height)
    val cutout = SegmentationMaskProcessor.cutout(framePixels, frame.width, alphaMask) ?: return null
    val previewPixels = SegmentationMaskProcessor.toPreviewPixels(alphaMask)
    val previewBitmap = createBitmap(alphaMask.width, alphaMask.height)
    previewBitmap.setPixels(previewPixels, 0, alphaMask.width, 0, 0, alphaMask.width, alphaMask.height)
    val cutoutBitmap = createBitmap(cutout.bbox.width, cutout.bbox.height)
    cutoutBitmap.setPixels(cutout.pixels, 0, cutout.bbox.width, 0, 0, cutout.bbox.width, cutout.bbox.height)
    return previewBitmap to cutoutBitmap
  }

  private fun getString(resId: Int): String = getApplication<Application>().getString(resId)

  companion object {
    private const val TAG = "StickerCreation"

    /** Long-edge cap for captured frames; segmentation runs at 512px internally anyway. */
    const val CAPTURE_MAX_DIMENSION = 640

    /** Watchdog for a single segmentation; generous even for cold-start CPU inference. */
    private const val SEGMENT_TIMEOUT_MS = 5_000L
  }
}
