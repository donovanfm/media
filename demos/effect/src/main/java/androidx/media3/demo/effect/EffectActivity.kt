/*
 * Copyright 2024 The Android Open Source Project
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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.demo.effect.ui.ColorsDropDownMenu
import androidx.media3.demo.effect.ui.GenericExposedDropdownMenu
import androidx.media3.demo.effect.ui.InputSelector
import androidx.media3.ui.compose.material3.Player
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EffectActivity : ComponentActivity() {

  private val viewModel: EffectViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { EffectDemo(viewModel) }
  }

  @OptIn(ExperimentalApi::class)
  @Composable
  private fun EffectDemo(viewModel: EffectViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val playlistHolderList by viewModel.playlistHolderList.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.errorMessage) {
      uiState.errorMessage?.let { message ->
        snackbarHostState.showSnackbar(message)
        viewModel.clearErrorMessage()
      }
    }

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
      Column(
        modifier = Modifier.fillMaxWidth().padding(paddingValues),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        InputSelector(
          playlistHolderList,
          onException = { message ->
            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
          },
        ) { mediaItems ->
          viewModel.selectMediaItems(mediaItems)
        }
        PlayerScreen(viewModel.exoPlayer)
        EffectControlsList(viewModel, uiState)
      }
    }
  }

  @OptIn(ExperimentalApi::class)
  @Composable
  private fun PlayerScreen(player: Player?) {
    var showControls by remember { mutableStateOf(true) }
    var interactionCount by remember { mutableStateOf(0) }

    fun resetControlsTimer() {
      interactionCount++
    }

    LaunchedEffect(interactionCount, player) {
      if (player != null) {
        showControls = true
        delay(CONTROLS_VISIBILITY_TIMEOUT_MS)
        showControls = false
      }
    }

    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(dimensionResource(id = R.dimen.android_view_height))
          .padding(all = dimensionResource(id = R.dimen.regular_padding))
          .clip(RoundedCornerShape(12.dp))
          .background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      if (player != null) {
        Player(
          player = player,
          showControls = showControls,
          modifier =
            Modifier.pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  // Using PointerEventPass.Initial is correct for a global "reset timer" behavior
                  // as it allows this component to see pointer events even if child components
                  // (like buttons in the Player UI) consume them.
                  awaitPointerEvent(PointerEventPass.Initial)
                  resetControlsTimer()
                }
              }
            },
          // Ensure that the internal Player composable doesn't have any gestures that might
          // conflict with this early interception.
        )
      } else {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
      }
    }
  }

  @Composable
  private fun EffectControlsList(viewModel: EffectViewModel, uiState: EffectUiState) {
    LazyColumn(Modifier.padding(vertical = dimensionResource(id = R.dimen.small_padding))) {
      item {
        EffectItem(
          name = stringResource(id = R.string.contrast),
          enabled = uiState.effectsEnabled,
          checked = uiState.contrastChecked,
          onCheckedChange = { checked -> viewModel.updateContrastChecked(checked) },
        ) {
          // The slider tracks the drag locally and only updates the ViewModel when the drag ends:
          // effects apply immediately on every state change, and rebuilding the player's effects
          // pipeline on each drag tick would be wasteful.
          var contrastValue by remember(uiState.contrastValue) {
            mutableFloatStateOf(uiState.contrastValue)
          }
          Row {
            Text(
              text = "%.2f".format(contrastValue),
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.padding(dimensionResource(id = R.dimen.large_padding)).weight(1f),
            )
            Slider(
              value = contrastValue,
              onValueChange = { contrastValue = it },
              onValueChangeFinished = { viewModel.updateContrast(contrastValue) },
              valueRange = -1f..1f,
              modifier = Modifier.weight(4f),
            )
          }
        }
      }
      item {
        EffectItem(
          name = stringResource(R.string.confetti_overlay),
          enabled = uiState.effectsEnabled,
          checked = uiState.confettiOverlayChecked,
          onCheckedChange = { checked -> viewModel.updateConfetti(checked) },
        )
      }
      item {
        EffectItem(
          name = stringResource(R.string.clock_overlay),
          enabled = uiState.effectsEnabled,
          checked = uiState.clockOverlayChecked,
          onCheckedChange = { checked -> viewModel.updateClock(checked) },
        )
      }
      item {
        EffectItem(
          name = stringResource(R.string.lottie_overlay),
          enabled = uiState.effectsEnabled && uiState.lottieEffectsLoaded,
          checked = uiState.lottieOverlayChecked,
          onCheckedChange = { checked -> viewModel.updateLottieChecked(checked) },
        ) {
          Column {
            Row {
              GenericExposedDropdownMenu(
                label = stringResource(R.string.lottie_asset),
                selectedValue =
                  uiState.lottieOverlayName ?: uiState.lottieOverlayOptions.firstOrNull() ?: "",
                options = uiState.lottieOverlayOptions,
                onOptionSelected = { viewModel.updateLottieName(it) },
                modifier =
                  Modifier.fillMaxWidth().padding(bottom = dimensionResource(R.dimen.large_padding)),
              )
            }
          }
        }
      }
      item {
        EffectItem(
          name = stringResource(R.string.custom_text_overlay),
          enabled = uiState.effectsEnabled,
          checked = uiState.textOverlayChecked,
          onCheckedChange = { checked -> viewModel.updateTextChecked(checked) },
        ) {
          Column {
            // Free text is committed on IME "done" or when focus leaves the field, rather than on
            // every keystroke: effects apply immediately on every state change, and rebuilding
            // the player's effects pipeline per character would be wasteful.
            var text by remember(uiState.textOverlayText) {
              mutableStateOf(uiState.textOverlayText ?: "")
            }
            val focusManager = LocalFocusManager.current
            OutlinedTextField(
              value = text,
              onValueChange = { text = it },
              label = { Text(stringResource(R.string.text)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
              keyboardActions =
                KeyboardActions(onDone = { focusManager.clearFocus() }),
              modifier =
                Modifier.fillMaxWidth()
                  .padding(bottom = dimensionResource(R.dimen.large_padding))
                  .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                      viewModel.updateText(text.ifEmpty { null })
                    }
                  },
            )
            Row {
              ColorsDropDownMenu(uiState.textOverlayColor) { color ->
                viewModel.updateTextColor(color)
              }
            }
            // Like the contrast slider, the alpha slider applies when the drag ends.
            var alphaValue by remember(uiState.textOverlayAlpha) {
              mutableFloatStateOf(uiState.textOverlayAlpha)
            }
            Row {
              Text(
                text = stringResource(R.string.alpha) + " = %.2f".format(alphaValue),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                  Modifier.padding(dimensionResource(id = R.dimen.large_padding)).weight(1f),
              )
              Slider(
                value = alphaValue,
                onValueChange = { newAlphaValue ->
                  alphaValue = "%.2f".format(Locale.ROOT, newAlphaValue).toFloat()
                },
                onValueChangeFinished = { viewModel.updateTextAlpha(alphaValue) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(2f),
              )
            }
          }
        }
      }
    }
  }

  @Composable
  fun EffectItem(
    name: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit = {},
  ) {
    Card(
      modifier =
        Modifier.padding(
            vertical = dimensionResource(id = R.dimen.small_padding),
            horizontal = dimensionResource(id = R.dimen.regular_padding),
          )
          .clickable(enabled = enabled && !checked) { onCheckedChange(!checked) }
    ) {
      Column(
        Modifier.padding(dimensionResource(id = R.dimen.large_padding))
          .animateContentSize(animationSpec = tween(durationMillis = 200, easing = LinearEasing))
      ) {
        Row {
          Column(Modifier.weight(1f).padding(dimensionResource(id = R.dimen.large_padding))) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
          }
          Checkbox(enabled = enabled, checked = checked, onCheckedChange = onCheckedChange)
        }
        if (checked) {
          content()
        }
      }
    }
  }
}

private const val CONTROLS_VISIBILITY_TIMEOUT_MS = 3000L
