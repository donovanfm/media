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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.effect.sticker.AnimatedStickerOverlay
import androidx.media3.demo.effect.sticker.SegmenterEngine
import androidx.media3.demo.effect.sticker.StickerAnimation
import androidx.media3.demo.effect.sticker.StickerAsset
import androidx.media3.demo.effect.sticker.StickerRepository
import androidx.media3.demo.effect.sticker.trimmedToOpaqueBounds
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [AndroidViewModel] for the effect demo application.
 *
 * This ViewModel manages the [ExoPlayer] instance, loads playlists, and keeps the player's video
 * effects in sync with the current [EffectUiState]: every effect-control change re-applies the
 * effects immediately (see [updateAndApply]) — there is no separate "apply" step.
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
    ExoPlayer.Builder(application).build().apply {
      playWhenReady = true
      addListener(
        object : Player.Listener {
          override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && effectsPendingResume) {
              effectsPendingResume = false
              applyEffects()
            }
          }
        }
      )
    }
  }

  private var lottieOverlayOptions: Map<String, Effect> = emptyMap()

  /** Whether effect changes arrived while paused and still need applying on resume. */
  private var effectsPendingResume = false

  private val stickerRepository = StickerRepository(application)

  // Decoded sticker bitmaps keyed by StickerAsset.id; the asset list is published via
  // EffectUiState while the heavyweight bitmaps stay here (same idiom as lottieOverlayOptions).
  private var stickerBitmaps: Map<String, Bitmap> = emptyMap()

  init {
    loadPlaylists()
    loadLottieEffects()
    loadStickerAssets()
    checkStickerCreationAvailable()
  }

  /**
   * Custom sticker creation needs the segmentation model, which builds made offline (or with
   * -PskipStickerModelDownload) don't bundle; disable the entry point instead of failing later.
   */
  private fun checkStickerCreationAvailable() {
    viewModelScope.launch {
      val available =
        withContext(Dispatchers.IO) {
          try {
            getApplication<Application>().assets.open(SegmenterEngine.MODEL_ASSET_PATH).use {}
            true
          } catch (e: IOException) {
            false
          }
        }
      _uiState.update { it.copy(stickerCreationAvailable = available) }
    }
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
   * becomes the selected entry; [placeAfterLoad] additionally enters placement mode for it.
   */
  private fun loadStickerAssets(selectId: String? = null, placeAfterLoad: Boolean = false) {
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
                  // Bundled images and stickers saved before trimming existed may carry
                  // transparent borders that keep their content off the video edges; trim at
                  // load. Animated first frames must NOT be trimmed: their dimensions have to
                  // match the saved animation frames or placement and playback disagree on size.
                  is StickerAsset.Bundled ->
                    getApplication<Application>().assets.open(asset.assetPath).use { stream ->
                      BitmapFactory.decodeStream(stream)?.let {
                        put(asset.id, it.trimmedToOpaqueBounds())
                      }
                    }
                  is StickerAsset.Static ->
                    put(asset.id, stickerRepository.loadBitmap(asset).trimmedToOpaqueBounds())
                  is StickerAsset.Animated -> put(asset.id, stickerRepository.loadFirstFrame(asset))
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
        if (placeAfterLoad && _uiState.value.selectedStickerAssetId == selectId) {
          startStickerPlacement()
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

  /**
   * Reloads the sticker list after a sticker was created, then drops the new sticker straight into
   * placement mode — creating a sticker almost always means wanting it on the video, so the flow
   * skips the manual Place step.
   */
  fun onStickerCreated(stickerId: String) {
    loadStickerAssets(selectId = stickerId, placeAfterLoad = true)
  }

  /** The URI of the currently playing media item, used as the sticker creation source video. */
  fun currentMediaUri(): Uri? = exoPlayer.currentMediaItem?.localConfiguration?.uri

  /**
   * Updates the player with a new list of [MediaItem]s to play, enabling effect controls in the UI.
   * The effect controls keep their values across media switches, and [applyEffects] carries them
   * over to the new media — the player always mirrors the controls. Placed stickers are the
   * exception: they snapshot the previous video's content rect and pixel size, so they can't be
   * carried to different media meaningfully and are cleared (as is any placement in progress).
   *
   * @param mediaItems The list of media items to play.
   */
  fun selectMediaItems(mediaItems: List<MediaItem>) {
    exoPlayer.apply {
      setMediaItems(mediaItems)
      prepare()
    }
    _uiState.update {
      it.copy(
        effectsEnabled = true,
        placedStickers = ImmutableList.of(),
        stickerPlacement = StickerPlacement.Inactive,
      )
    }
    applyEffects()
  }

  /**
   * Applies [transform] to the UI state and immediately re-applies the video effects, unless the
   * state is unchanged (which avoids needlessly rebuilding the player's effects pipeline). Every
   * control that changes what renders in the video funnels through here — the player always
   * reflects what the controls show. UI-only state (selections, placement previews, layout sizes)
   * uses a plain state update instead, since it doesn't affect the player.
   */
  private inline fun updateAndApply(transform: (EffectUiState) -> EffectUiState) {
    val oldState = _uiState.getAndUpdate(transform)
    if (_uiState.value == oldState) {
      return
    }
    applyEffects()
  }

  /**
   * Toggles whether the contrast effect is enabled in the UI controls.
   *
   * @param checked Whether the contrast effect checkbox is checked.
   */
  fun updateContrastChecked(checked: Boolean) {
    updateAndApply {
      val value = if (checked) it.contrastValue else 0f
      it.copy(contrastChecked = checked, contrastValue = value)
    }
  }

  /**
   * Updates the contrast effect value in the UI controls.
   *
   * @param value The contrast value in the range of -1f to 1f.
   */
  fun updateContrast(value: Float) {
    updateAndApply { it.copy(contrastValue = value) }
  }

  /**
   * Toggles whether the confetti overlay is enabled in the UI controls.
   *
   * @param checked Whether the confetti overlay checkbox is checked.
   */
  fun updateConfetti(checked: Boolean) {
    updateAndApply { it.copy(confettiOverlayChecked = checked) }
  }

  /**
   * Toggles whether the clock overlay is enabled in the UI controls.
   *
   * @param checked Whether the clock overlay checkbox is checked.
   */
  fun updateClock(checked: Boolean) {
    updateAndApply { it.copy(clockOverlayChecked = checked) }
  }

  /**
   * Toggles whether the Lottie animation overlay is enabled in the UI controls.
   *
   * @param checked Whether the Lottie overlay checkbox is checked.
   */
  fun updateLottieChecked(checked: Boolean) {
    updateAndApply { it.copy(lottieOverlayChecked = checked) }
  }

  /**
   * Updates the selected Lottie asset name in the UI controls.
   *
   * @param name The display name of the Lottie effect to apply.
   */
  fun updateLottieName(name: String) {
    updateAndApply { it.copy(lottieOverlayName = name) }
  }

  /**
   * Toggles whether the custom text overlay is enabled in the UI controls.
   *
   * @param checked Whether the custom text overlay checkbox is checked.
   */
  fun updateTextChecked(checked: Boolean) {
    updateAndApply { it.copy(textOverlayChecked = checked) }
  }

  /**
   * Updates the text content for the custom text overlay in the UI controls.
   *
   * @param text The text string to display, or null if empty.
   */
  fun updateText(text: String?) {
    updateAndApply { it.copy(textOverlayText = text) }
  }

  /**
   * Updates the font color for the custom text overlay in the UI controls.
   *
   * @param color The [Color] to apply to the overlay text.
   */
  fun updateTextColor(color: Color) {
    updateAndApply { it.copy(textOverlayColor = color) }
  }

  /**
   * Updates the alpha scale (transparency) for the custom text overlay in the UI controls.
   *
   * @param alpha The alpha scale from 0f (transparent) to 1f (opaque).
   */
  fun updateTextAlpha(alpha: Float) {
    updateAndApply { it.copy(textOverlayAlpha = alpha) }
  }

  /**
   * Toggles whether placed sticker overlays are included in the applied effects.
   *
   * @param checked Whether the sticker overlay checkbox is checked.
   */
  fun updateStickerChecked(checked: Boolean) {
    updateAndApply { it.copy(stickerOverlayChecked = checked) }
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
   * Updates the preset placement animation. When a sticker is currently being placed, the change
   * applies to it live (so choosing a preset after auto-placement works as expected); recorded
   * stickers keep [StickerAnimation.NONE] since they play their own animation.
   *
   * @param animation The preset to use.
   */
  fun updateSelectedStickerAnimation(animation: StickerAnimation) {
    _uiState.update {
      val placement = it.stickerPlacement
      it.copy(
        selectedStickerAnimation = animation,
        stickerPlacement =
          if (placement is StickerPlacement.Placing && placement.animated == null) {
            placement.copy(animation = animation)
          } else {
            placement
          },
      )
    }
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
   * draggable preview centered over the video. Animated stickers load their frames first (on IO);
   * the placement preview uses the first frame either way.
   */
  fun startStickerPlacement() {
    val currentState = _uiState.value
    if (currentState.stickerPlacement is StickerPlacement.Placing) {
      return
    }
    val assetId = currentState.selectedStickerAssetId ?: return
    val asset = currentState.stickerAssets.find { it.id == assetId } ?: return
    val bitmap = stickerBitmaps[assetId] ?: return
    viewModelScope.launch {
      val animated =
        if (asset is StickerAsset.Animated) {
          try {
            stickerRepository.loadAnimated(asset)
          } catch (e: IOException) {
            _uiState.update {
              it.copy(
                errorMessage =
                  getApplication<Application>().getString(R.string.sticker_loading_error)
              )
            }
            return@launch
          }
        } else {
          null
        }
      if (_uiState.value.stickerPlacement is StickerPlacement.Placing) {
        return@launch
      }
      exoPlayer.pause()
      val videoSize = exoPlayer.videoSize
      val contentRect =
        StickerGeometry.videoContentRect(
          _uiState.value.playerBoxSize,
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
              animated = animated,
              // Recorded stickers bring their own animation; the preset dropdown is disabled for
              // them in the UI, and NONE here keeps behavior consistent with that promise.
              animation =
                if (animated != null) {
                  StickerAnimation.NONE
                } else {
                  _uiState.value.selectedStickerAnimation
                },
            )
        )
      }
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
    // Removing the sticker from the placed list re-applies the effects without it: otherwise its
    // previously applied copy stays baked into the video next to the draggable preview, which
    // reads as a duplicate.
    updateAndApply {
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
            animated = sticker.animated,
            animation = sticker.animation,
          ),
        // Reflect the edited sticker's preset in the Animation dropdown.
        selectedStickerAnimation = sticker.animation,
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
   * Commits the sticker being placed, enables the sticker overlay effect (which applies it to the
   * video), and resumes playback.
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
        animated = placing.animated,
        animation = placing.animation,
      )
    updateAndApply {
      it.copy(
        placedStickers =
          ImmutableList.builder<PlacedSticker>()
            .addAll(it.placedStickers)
            .add(placedSticker)
            .build(),
        stickerPlacement = StickerPlacement.Inactive,
        stickerOverlayChecked = true,
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
    if (placing.original != null) {
      // Editing un-applied the original when placement began; bring it back on screen.
      updateAndApply {
        it.copy(
          placedStickers =
            ImmutableList.builder<PlacedSticker>()
              .addAll(it.placedStickers)
              .add(placing.original)
              .build(),
          stickerPlacement = StickerPlacement.Inactive,
        )
      }
    } else {
      // Nothing was applied yet; leaving placement mode is a UI-only change.
      _uiState.update { it.copy(stickerPlacement = StickerPlacement.Inactive) }
    }
    exoPlayer.play()
  }

  /**
   * Removes a committed sticker from the video.
   *
   * @param id The [PlacedSticker.id] of the sticker to remove.
   */
  fun removePlacedSticker(id: UUID) {
    if (_uiState.value.stickerPlacement is StickerPlacement.Placing) {
      // Deleting mid-placement would fight the placement state; the row is disabled in the UI
      // too, this is belt-and-braces.
      return
    }
    updateAndApply {
      it.copy(
        placedStickers =
          ImmutableList.copyOf(it.placedStickers.filter { placed -> placed.id != id })
      )
    }
  }

  /**
   * Builds the video effects list from the current [EffectUiState] and applies it to the underlying
   * [ExoPlayer]. While playback is paused the change is deferred until it resumes.
   */
  private fun applyEffects() {
    if (exoPlayer.playbackState == Player.STATE_READY && !exoPlayer.playWhenReady) {
      // A paused player renders no new frames, so the change would not be visible until playback
      // resumes anyway, and effect changes queued while paused can stall playback on resume.
      // Redrawing the paused frame needs VideoFrameProcessor.REDRAW with the replayable frame
      // cache, which ExoPlayer does not expose yet (b/391109644).
      effectsPendingResume = true
      return
    }
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
        val animated = sticker.animated
        overlaysBuilder.add(
          when {
            animated != null ->
              AnimatedStickerOverlay(
                animated.frames,
                animated.timestampsUs,
                animated.durationUs,
                sticker.animation,
                placement.anchorX,
                placement.anchorY,
                placement.scale,
              )
            sticker.animation != StickerAnimation.NONE ->
              AnimatedStickerOverlay.forStaticBitmap(
                sticker.bitmap,
                sticker.animation,
                placement.anchorX,
                placement.anchorY,
                placement.scale,
              )
            else ->
              BitmapOverlay.createStaticBitmapOverlay(
                sticker.bitmap,
                StaticOverlaySettings.Builder()
                  .setBackgroundFrameAnchor(placement.anchorX, placement.anchorY)
                  .setScale(placement.scale, placement.scale)
                  .build(),
              )
          }
        )
      }
    }

    val overlays = overlaysBuilder.build()
    if (overlays.isNotEmpty()) {
      listBuilder.add(OverlayEffect(overlays))
    }

    exoPlayer.apply {
      setVideoEffects(listBuilder.build())
      // No-op unless the player is idle, so this only re-prepares after a playback error.
      prepare()
    }
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
