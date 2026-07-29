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
package androidx.media3.demo.effect

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.demo.effect.sticker.StickerAsset
import androidx.media3.demo.effect.sticker.StickerRepository
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [AndroidViewModel] for the effect demo application.
 *
 * This ViewModel manages the [ExoPlayer] instance, loads playlists, and provides methods to update
 * and apply video effects based on the current [EffectUiState].
 */
@OptIn(UnstableApi::class)
internal class EffectViewModel(application: Application) : AndroidViewModel(application) {

  private val _playlistHolderList = MutableStateFlow<List<PlaylistHolder>>(emptyList())
  /** A [StateFlow] emitting the list of available playlists loaded from JSON assets. */
  val playlistHolderList: StateFlow<List<PlaylistHolder>> = _playlistHolderList.asStateFlow()

  private val _uiState = MutableStateFlow(EffectUiState())
  /** A [StateFlow] emitting the current UI state representing effect controls and settings. */
  val uiState: StateFlow<EffectUiState> = _uiState.asStateFlow()

  /**
   * The [ExoPlayer] instance used for media playback, initialized lazily and automatically released
   * when this ViewModel is cleared.
   */
  val exoPlayer: ExoPlayer by lazy {
    ExoPlayer.Builder(application).build().apply { playWhenReady = true }
  }

  private var lottieOverlayOptions: Map<String, Effect> = emptyMap()

  private val stickerRepository = StickerRepository(application)

  // Decoded sticker bitmaps keyed by StickerAsset.id; the asset list is published via
  // EffectUiState while the heavyweight bitmaps stay here (same idiom as lottieOverlayOptions).
  private var stickerBitmaps: Map<String, Bitmap> = emptyMap()

  init {
    loadPlaylists()
    loadLottieEffects()
    loadStickerAssets()
  }

  private fun loadPlaylists() {
    viewModelScope.launch {
      try {
        val playlists =
          withContext(Dispatchers.IO) {
            loadPlaylistsFromJson(JSON_FILENAME, getApplication(), "EffectViewModel")
          }
        _playlistHolderList.value = playlists
        if (playlists.isNotEmpty()) {
          selectMediaItems(playlists.first().mediaItems)
        } else {
          _uiState.update {
            it.copy(
              errorMessage =
                getApplication<Application>().getString(R.string.no_loaded_playlists_error)
            )
          }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            errorMessage =
              getApplication<Application>()
                .getString(R.string.playlist_loading_error, JSON_FILENAME, e)
          )
        }
      }
    }
  }

  private fun loadLottieEffects() {
    viewModelScope.launch {
      val options =
        withContext(Dispatchers.IO) {
          buildMap {
            LottieEffectFactory.buildAvailableEffects(getApplication()).forEach { (name, effect) ->
              put(name, effect)
            }
          }
        }
      lottieOverlayOptions = options
      _uiState.update {
        it.copy(
          lottieOverlayOptions = ImmutableList.copyOf(options.keys),
          lottieOverlayName =
            getApplication<Application>().getString(R.string.lottie_effect_name_counter),
          lottieEffectsLoaded = true,
        )
      }
    }
  }

  /**
   * Loads the sticker picker contents — bundled assets plus user-created stickers — and decodes
   * their bitmaps off the main thread. When [selectId] is given (a freshly created sticker), it
   * becomes the selected entry.
   */
  private fun loadStickerAssets(selectId: String? = null) {
    viewModelScope.launch {
      try {
        val (assets, bitmaps) =
          withContext(Dispatchers.IO) {
            val resources = getApplication<Application>().resources
            val names = resources.getStringArray(R.array.sticker_asset_names)
            val paths = resources.getStringArray(R.array.sticker_asset_paths)
            val bundled = names.indices.map { StickerAsset.Bundled(names[it], paths[it]) }
            val custom = stickerRepository.loadAll()
            val allAssets = bundled + custom
            val bitmaps = buildMap {
              for (asset in allAssets) {
                when (asset) {
                  is StickerAsset.Bundled ->
                    getApplication<Application>().assets.open(asset.assetPath).use { stream ->
                      BitmapFactory.decodeStream(stream)?.let { put(asset.id, it) }
                    }
                  is StickerAsset.Static -> put(asset.id, stickerRepository.loadBitmap(asset))
                  is StickerAsset.Animated ->
                    put(asset.id, stickerRepository.loadFirstFrame(asset))
                }
              }
            }
            allAssets.filter { bitmaps.containsKey(it.id) } to bitmaps
          }
        stickerBitmaps = bitmaps
        _uiState.update {
          it.copy(
            stickerAssets = ImmutableList.copyOf(assets),
            selectedStickerAssetId =
              selectId?.takeIf { id -> bitmaps.containsKey(id) }
                ?: it.selectedStickerAssetId?.takeIf { id -> bitmaps.containsKey(id) }
                ?: assets.firstOrNull()?.id,
            stickerAssetsLoaded = assets.isNotEmpty(),
          )
        }
      } catch (e: IOException) {
        _uiState.update {
          it.copy(
            errorMessage = getApplication<Application>().getString(R.string.sticker_loading_error)
          )
        }
      }
    }
  }

  /** Reloads the sticker list after a sticker was created, selecting the new one. */
  fun refreshStickers(selectId: String?) {
    loadStickerAssets(selectId)
  }

  /** The URI of the currently playing media item, used as the sticker creation source video. */
  fun currentMediaUri(): Uri? = exoPlayer.currentMediaItem?.localConfiguration?.uri

  /**
   * Updates the player with a new list of [MediaItem]s to play, clearing any active video effects
   * and enabling effect controls in the UI.
   *
   * @param mediaItems The list of media items to play.
   */
  fun selectMediaItems(mediaItems: List<MediaItem>) {
    exoPlayer.apply {
      setMediaItems(mediaItems)
      setVideoEffects(emptyList())
      prepare()
    }
    _uiState.update { it.copy(effectsEnabled = true, effectsChanged = false) }
  }

  /**
   * Toggles whether the contrast effect is enabled in the UI controls.
   *
   * @param checked Whether the contrast effect checkbox is checked.
   */
  fun updateContrastChecked(checked: Boolean) {
    _uiState.update {
      val value = if (checked) it.contrastValue else 0f
      it.copy(contrastChecked = checked, contrastValue = value, effectsChanged = true)
    }
  }

  /**
   * Updates the contrast effect value in the UI controls.
   *
   * @param value The contrast value in the range of -1f to 1f.
   */
  fun updateContrast(value: Float) {
    _uiState.update { it.copy(contrastValue = value, effectsChanged = true) }
  }

  /**
   * Toggles whether the confetti overlay is enabled in the UI controls.
   *
   * @param checked Whether the confetti overlay checkbox is checked.
   */
  fun updateConfetti(checked: Boolean) {
    _uiState.update { it.copy(confettiOverlayChecked = checked, effectsChanged = true) }
  }

  /**
   * Toggles whether the clock overlay is enabled in the UI controls.
   *
   * @param checked Whether the clock overlay checkbox is checked.
   */
  fun updateClock(checked: Boolean) {
    _uiState.update { it.copy(clockOverlayChecked = checked, effectsChanged = true) }
  }

  /**
   * Toggles whether the Lottie animation overlay is enabled in the UI controls.
   *
   * @param checked Whether the Lottie overlay checkbox is checked.
   */
  fun updateLottieChecked(checked: Boolean) {
    _uiState.update { it.copy(lottieOverlayChecked = checked, effectsChanged = true) }
  }

  /**
   * Updates the selected Lottie asset name in the UI controls.
   *
   * @param name The display name of the Lottie effect to apply.
   */
  fun updateLottieName(name: String) {
    _uiState.update { it.copy(lottieOverlayName = name, effectsChanged = true) }
  }

  /**
   * Toggles whether the custom text overlay is enabled in the UI controls.
   *
   * @param checked Whether the custom text overlay checkbox is checked.
   */
  fun updateTextChecked(checked: Boolean) {
    _uiState.update {
      val effectsChanged = !checked // Replicating original logic
      it.copy(textOverlayChecked = checked, effectsChanged = effectsChanged)
    }
  }

  /**
   * Updates the text content for the custom text overlay in the UI controls.
   *
   * @param text The text string to display, or null if empty.
   */
  fun updateText(text: String?) {
    _uiState.update { it.copy(textOverlayText = text, effectsChanged = true) }
  }

  /**
   * Updates the font color for the custom text overlay in the UI controls.
   *
   * @param color The [Color] to apply to the overlay text.
   */
  fun updateTextColor(color: Color) {
    _uiState.update {
      it.copy(textOverlayColor = color, effectsChanged = it.textOverlayText != null)
    }
  }

  /**
   * Updates the alpha scale (transparency) for the custom text overlay in the UI controls.
   *
   * @param alpha The alpha scale from 0f (transparent) to 1f (opaque).
   */
  fun updateTextAlpha(alpha: Float) {
    _uiState.update {
      it.copy(textOverlayAlpha = alpha, effectsChanged = it.textOverlayText != null)
    }
  }

  /**
   * Toggles whether placed sticker overlays are included in the applied effects.
   *
   * @param checked Whether the sticker overlay checkbox is checked.
   */
  fun updateStickerChecked(checked: Boolean) {
    _uiState.update { it.copy(stickerOverlayChecked = checked, effectsChanged = true) }
  }

  /**
   * Updates the selected sticker asset in the UI controls. Selection alone doesn't change the
   * applied effects; it only chooses what the next placement uses.
   *
   * @param id The [StickerAsset.id] of the sticker asset.
   */
  fun updateSelectedStickerAsset(id: String) {
    _uiState.update { it.copy(selectedStickerAssetId = id) }
  }

  /**
   * Records the measured size of the player box so placement can compute the video content rect.
   *
   * @param size The size of the player box in pixels.
   */
  fun updatePlayerBoxSize(size: Size) {
    _uiState.update { it.copy(playerBoxSize = size) }
  }

  /**
   * Enters placement mode for the currently selected sticker asset: pauses playback and shows a
   * draggable preview centered over the video.
   */
  fun startStickerPlacement() {
    val currentState = _uiState.value
    if (currentState.stickerPlacement is StickerPlacement.Placing) {
      return
    }
    val assetId = currentState.selectedStickerAssetId ?: return
    val asset = currentState.stickerAssets.find { it.id == assetId } ?: return
    val bitmap = stickerBitmaps[assetId] ?: return
    exoPlayer.pause()
    val videoSize = exoPlayer.videoSize
    val contentRect =
      StickerGeometry.videoContentRect(
        currentState.playerBoxSize,
        videoSize.width,
        videoSize.height,
        videoSize.pixelWidthHeightRatio,
      )
    _uiState.update {
      it.copy(
        stickerPlacement =
          StickerPlacement.Placing(
            original = null,
            assetName = asset.name,
            bitmap = bitmap,
            transform =
              StickerGeometry.centeredTransform(bitmap.width, bitmap.height, contentRect.size),
            contentRect = contentRect,
            videoPixelWidth = videoSize.width,
          )
      )
    }
  }

  /**
   * Re-enters placement mode for a committed sticker, restoring its transform so it can be moved,
   * rescaled, or cancelled back to its original position.
   *
   * @param id The [PlacedSticker.id] of the sticker to edit.
   */
  fun editPlacedSticker(id: UUID) {
    val currentState = _uiState.value
    if (currentState.stickerPlacement is StickerPlacement.Placing) {
      return
    }
    val sticker = currentState.placedStickers.find { it.id == id } ?: return
    exoPlayer.pause()
    _uiState.update {
      it.copy(
        placedStickers =
          ImmutableList.copyOf(it.placedStickers.filter { placed -> placed.id != id }),
        stickerPlacement =
          StickerPlacement.Placing(
            original = sticker,
            assetName = sticker.assetName,
            bitmap = sticker.bitmap,
            transform = sticker.transform,
            contentRect = sticker.contentRect,
            videoPixelWidth = sticker.videoPixelWidth,
          ),
      )
    }
  }

  /**
   * Applies one pan+zoom gesture event to the sticker being placed. Pan and zoom from the same
   * pointer event are applied atomically so clamping stays consistent.
   */
  fun transformSticker(centroid: Offset, pan: Offset, zoom: Float) {
    _uiState.update {
      val placing = it.stickerPlacement as? StickerPlacement.Placing ?: return@update it
      it.copy(
        stickerPlacement =
          placing.copy(
            transform =
              StickerGeometry.applyGesture(
                placing.transform,
                centroid,
                pan,
                zoom,
                placing.bitmap.width,
                placing.bitmap.height,
                placing.contentRect.size,
              )
          )
      )
    }
  }

  /**
   * Commits the sticker being placed, enables the sticker overlay effect, and resumes playback.
   * The sticker renders once effects are applied.
   */
  fun commitStickerPlacement() {
    val placing = _uiState.value.stickerPlacement as? StickerPlacement.Placing ?: return
    val placedSticker =
      PlacedSticker(
        id = placing.original?.id ?: UUID.randomUUID(),
        assetName = placing.assetName,
        bitmap = placing.bitmap,
        transform = placing.transform,
        contentRect = placing.contentRect,
        videoPixelWidth = placing.videoPixelWidth,
      )
    _uiState.update {
      it.copy(
        placedStickers =
          ImmutableList.builder<PlacedSticker>().addAll(it.placedStickers).add(placedSticker)
            .build(),
        stickerPlacement = StickerPlacement.Inactive,
        stickerOverlayChecked = true,
        effectsChanged = true,
      )
    }
    exoPlayer.play()
  }

  /**
   * Exits placement mode without committing. When editing an existing sticker, restores it
   * unchanged.
   */
  fun cancelStickerPlacement() {
    val placing = _uiState.value.stickerPlacement as? StickerPlacement.Placing ?: return
    _uiState.update {
      it.copy(
        placedStickers =
          placing.original?.let { original ->
            ImmutableList.builder<PlacedSticker>().addAll(it.placedStickers).add(original).build()
          } ?: it.placedStickers,
        stickerPlacement = StickerPlacement.Inactive,
      )
    }
    exoPlayer.play()
  }

  /**
   * Removes a committed sticker. The change takes effect the next time effects are applied.
   *
   * @param id The [PlacedSticker.id] of the sticker to remove.
   */
  fun removePlacedSticker(id: UUID) {
    _uiState.update {
      it.copy(
        placedStickers =
          ImmutableList.copyOf(it.placedStickers.filter { placed -> placed.id != id }),
        effectsChanged = true,
      )
    }
  }

  /**
   * Builds the video effects list based on the current [EffectUiState] and applies them to the
   * underlying [ExoPlayer].
   */
  fun applyEffects() {
    val currentState = _uiState.value
    val listBuilder = ImmutableList.builder<Effect>()

    if (currentState.contrastChecked && currentState.contrastValue != 0f) {
      listBuilder.add(Contrast(currentState.contrastValue))
    }

    val overlaysBuilder = ImmutableList.builder<TextureOverlay>()
    if (currentState.confettiOverlayChecked) {
      overlaysBuilder.add(ConfettiOverlay())
    }

    if (currentState.clockOverlayChecked) {
      overlaysBuilder.add(ClockOverlay())
    }

    if (currentState.lottieOverlayChecked) {
      val lottieEffect = lottieOverlayOptions[currentState.lottieOverlayName]
      lottieEffect?.let { listBuilder.add(it) }
    }

    val textOverlayText = currentState.textOverlayText
    if (currentState.textOverlayChecked && textOverlayText != null) {
      val spannableOverlayText = SpannableString(textOverlayText)
      spannableOverlayText.setSpan(
        ForegroundColorSpan(currentState.textOverlayColor.toArgb()),
        /* start= */ 0,
        textOverlayText.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
      val staticOverlaySettings =
        StaticOverlaySettings.Builder().setAlphaScale(currentState.textOverlayAlpha).build()
      overlaysBuilder.add(
        TextOverlay.createStaticTextOverlay(spannableOverlayText, staticOverlaySettings)
      )
    }
    // Stickers are added last so they composite on top of the other texture overlays.
    if (currentState.stickerOverlayChecked) {
      for (sticker in currentState.placedStickers) {
        val placement =
          StickerGeometry.toOverlayPlacement(
            sticker.transform,
            sticker.bitmap.width,
            sticker.bitmap.height,
            sticker.contentRect.size,
            sticker.videoPixelWidth,
          )
        overlaysBuilder.add(
          BitmapOverlay.createStaticBitmapOverlay(
            sticker.bitmap,
            StaticOverlaySettings.Builder()
              .setBackgroundFrameAnchor(placement.anchorX, placement.anchorY)
              .setScale(placement.scale, placement.scale)
              .build(),
          )
        )
      }
    }

    val overlays = overlaysBuilder.build()
    if (overlays.isNotEmpty()) {
      listBuilder.add(OverlayEffect(overlays))
    }

    exoPlayer.apply {
      setVideoEffects(listBuilder.build())
      prepare()
    }
    _uiState.update { it.copy(effectsChanged = false) }
  }

  /** Clears any active error message in the [EffectUiState]. */
  fun clearErrorMessage() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  override fun onCleared() {
    super.onCleared()
    exoPlayer.release()
  }

  private companion object {
    private const val JSON_FILENAME = "media.playlist.json"
  }
}
