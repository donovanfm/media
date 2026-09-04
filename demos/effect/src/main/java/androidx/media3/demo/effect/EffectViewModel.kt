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
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
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

  init {
    loadPlaylists()
    loadLottieEffects()
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
   * Updates the player with a new list of [MediaItem]s to play, enabling effect controls in the
   * UI. The effect controls keep their values across media switches, and [applyEffects] carries
   * them over to the new media — the player always mirrors the controls.
   *
   * @param mediaItems The list of media items to play.
   */
  fun selectMediaItems(mediaItems: List<MediaItem>) {
    exoPlayer.apply {
      setMediaItems(mediaItems)
      prepare()
    }
    _uiState.update { it.copy(effectsEnabled = true) }
    applyEffects()
  }

  /**
   * Applies [transform] to the UI state and immediately re-applies the video effects, unless the
   * state is unchanged (which avoids needlessly rebuilding the player's effects pipeline). Every
   * effect control funnels through here — the player always reflects what the controls show.
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
   * Builds the video effects list based on the current [EffectUiState] and applies them to the
   * underlying [ExoPlayer]. While playback is paused the change is deferred until it resumes.
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
