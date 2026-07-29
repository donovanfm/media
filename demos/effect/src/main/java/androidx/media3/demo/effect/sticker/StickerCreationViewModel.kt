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
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.effect.R
import androidx.media3.demo.effect.sticker.VideoCoordinateMapper.NormalizedPoint
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            _uiState.update { it.copy(videoAspectRatio = aspectRatio) }
          }
        }
      )
    }
  }

  private val segmenterEngine = SegmenterEngine(getApplication())
  private val repository = StickerRepository(getApplication())

  private var frameSource: FrameSource? = null
  private var pendingCutout: Bitmap? = null

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

  /** Sets the video to cut stickers from, or moves to [CreationPhase.AwaitingVideo] when null. */
  fun setMediaUri(uri: Uri?) {
    if (uri == null) {
      _uiState.update { it.copy(phase = CreationPhase.AwaitingVideo) }
      return
    }
    player.setMediaItem(MediaItem.fromUri(uri))
    player.prepare()
    player.play()
    pendingCutout = null
    _uiState.update { it.copy(phase = CreationPhase.Playing) }
  }

  /** Attaches or detaches the UI's frame source; pass null when the surface goes away. */
  fun attachFrameSource(source: FrameSource?) {
    frameSource = source
  }

  /**
   * Cuts a static sticker: pauses playback, captures the current frame, segments the object at
   * [point], and shows the mask preview.
   */
  fun createStaticSticker(point: NormalizedPoint) {
    val currentPhase = _uiState.value.phase
    if (currentPhase != CreationPhase.Playing || !_uiState.value.segmenterReady) {
      return
    }
    viewModelScope.launch {
      player.pause()
      val frame = frameSource?.captureFrame(null)
      if (frame == null) {
        _uiState.update {
          it.copy(
            phase = CreationPhase.Playing,
            errorMessage = getString(R.string.sticker_error_frame_capture),
          )
        }
        player.play()
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
          player.play()
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
        player.play()
      }
    }
  }

  /** Updates the name the sticker will be saved under. */
  fun setStickerName(name: String) {
    _uiState.update { it.copy(stickerName = name) }
  }

  /** Persists the pending cutout and emits its id via [savedStickerId]. */
  fun saveSticker() {
    val cutout = pendingCutout ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(phase = CreationPhase.Saving) }
      try {
        val name =
          _uiState.value.stickerName.ifBlank { getString(R.string.sticker_default_name) }
        val asset = repository.saveStatic(cutout, name)
        _savedStickerId.value = asset.id
      } catch (e: Exception) {
        if (e is IOException || e is RuntimeException) {
          _uiState.update {
            it.copy(
              phase = CreationPhase.Playing,
              errorMessage = getString(R.string.sticker_error_save),
            )
          }
          player.play()
        } else {
          throw e
        }
      }
    }
  }

  /** Discards the current preview and resumes playback. */
  fun discardPreview() {
    pendingCutout = null
    _uiState.update { it.copy(phase = CreationPhase.Playing) }
    player.play()
  }

  /** Clears any active error message. */
  fun clearErrorMessage() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  override fun onCleared() {
    super.onCleared()
    player.release()
    segmenterEngine.close()
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
    /** Long-edge cap for captured frames; segmentation runs at 512px internally anyway. */
    const val CAPTURE_MAX_DIMENSION = 640
  }
}
