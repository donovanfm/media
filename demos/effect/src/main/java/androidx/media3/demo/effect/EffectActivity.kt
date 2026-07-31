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

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.demo.effect.sticker.AnimationModulation
import androidx.media3.demo.effect.sticker.CreateStickerContract
import androidx.media3.demo.effect.sticker.StickerAnimation
import androidx.media3.demo.effect.sticker.StickerAsset
import androidx.media3.demo.effect.sticker.modulationAt
import androidx.media3.demo.effect.ui.ColorsDropDownMenu
import androidx.media3.demo.effect.ui.GenericExposedDropdownMenu
import androidx.media3.demo.effect.ui.InputSelector
import androidx.media3.ui.compose.material3.Player
import java.util.Locale
import kotlin.math.roundToInt
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
        PlayerScreen(
          player = viewModel.exoPlayer,
          stickerPlacement = uiState.stickerPlacement,
          onPlayerBoxSized = { size -> viewModel.updatePlayerBoxSize(size) },
          onStickerTransform = { centroid, pan, zoom ->
            viewModel.transformSticker(centroid, pan, zoom)
          },
        )
        EffectControls(viewModel, uiState)
      }
    }
  }

  @OptIn(ExperimentalApi::class)
  @Composable
  private fun PlayerScreen(
    player: Player?,
    stickerPlacement: StickerPlacement,
    onPlayerBoxSized: (Size) -> Unit,
    onStickerTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
  ) {
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
          .background(Color.Black)
          .onSizeChanged { size -> onPlayerBoxSized(size.toSize()) },
      contentAlignment = Alignment.Center,
    ) {
      if (player != null) {
        Player(
          player = player,
          // Hide the controls during sticker placement so they don't compete with the gesture
          // layer drawn on top.
          showControls = showControls && stickerPlacement is StickerPlacement.Inactive,
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
        if (stickerPlacement is StickerPlacement.Placing) {
          DraggableSticker(
            bitmap = stickerPlacement.bitmap,
            transform = stickerPlacement.transform,
            contentRect = stickerPlacement.contentRect,
            animation = stickerPlacement.animation,
            onTransform = onStickerTransform,
            modifier = Modifier.align(Alignment.TopStart),
          )
        }
      } else {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
      }
    }
  }

  /**
   * The draggable, pinch-scalable sticker preview drawn over the video during placement. The
   * gesture area is sized and positioned to [contentRect] (the letterboxed video area) so the
   * sticker can't be placed on the black bars. A dashed border marks the preview as editable, and
   * the selected [animation] preset plays live on it so the Animation dropdown gives immediate
   * feedback even though the video is paused.
   */
  @Composable
  private fun DraggableSticker(
    bitmap: Bitmap,
    transform: StickerTransform,
    contentRect: Rect,
    animation: StickerAnimation,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    modifier: Modifier = Modifier,
  ) {
    val density = LocalDensity.current
    var modulation by remember { mutableStateOf(AnimationModulation.IDENTITY) }
    LaunchedEffect(animation) {
      if (animation == StickerAnimation.NONE) {
        modulation = AnimationModulation.IDENTITY
        return@LaunchedEffect
      }
      val startNanos = withFrameNanos { it }
      while (true) {
        withFrameNanos { now -> modulation = animation.modulationAt((now - startNanos) / 1000) }
      }
    }
    Box(
      modifier
        .offset { IntOffset(contentRect.left.roundToInt(), contentRect.top.roundToInt()) }
        .size(
          with(density) { contentRect.width.toDp() },
          with(density) { contentRect.height.toDp() },
        )
        .clipToBounds()
        .pointerInput(Unit) {
          detectTransformGestures { centroid, pan, zoom, _ -> onTransform(centroid, pan, zoom) }
        }
    ) {
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.sticker_preview),
        modifier =
          Modifier.offset {
              IntOffset(transform.offset.x.roundToInt(), transform.offset.y.roundToInt())
            }
            .graphicsLayer(
              scaleX = transform.scale * modulation.scaleFactor,
              scaleY = transform.scale * modulation.scaleFactor,
              rotationZ = modulation.rotationDegrees,
              // Anchor offsets span [-1, 1] across the content rect, +y up (overlay space).
              translationX = modulation.anchorOffsetX * contentRect.width / 2f,
              translationY = -modulation.anchorOffsetY * contentRect.height / 2f,
            )
            .drawBehind {
              drawRect(
                color = Color.White.copy(alpha = 0.9f),
                style =
                  Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)),
                  ),
              )
            }
            .wrapContentSize(align = Alignment.TopStart, unbounded = true),
      )
    }
  }

  @Composable
  private fun EffectControls(viewModel: EffectViewModel, uiState: EffectUiState) {
    val placement = uiState.stickerPlacement
    if (placement is StickerPlacement.Placing) {
      // Placement is a distinct mode: a banner + compact toolbar replace the Apply button, and
      // the effects list behind is scrimmed. Committing applies the sticker immediately without
      // confirming other effects' pending changes (see EffectViewModel.applyStickerChange).
      Text(
        text = stringResource(R.string.sticker_placement_banner, placement.assetName),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.large_padding)),
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.large_padding)),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.fillMaxWidth()
            .padding(
              horizontal = dimensionResource(R.dimen.large_padding),
              vertical = dimensionResource(R.dimen.small_padding),
            ),
      ) {
        GenericExposedDropdownMenu(
          label = stringResource(R.string.sticker_animation),
          selectedValue = uiState.selectedStickerAnimation,
          options = StickerAnimation.entries,
          onOptionSelected = { viewModel.updateSelectedStickerAnimation(it) },
          modifier = Modifier.weight(1f),
          enabled = placement.animated == null,
          itemLabelProvider = { stringResource(it.labelRes()) },
        )
        Button(onClick = { viewModel.commitStickerPlacement() }) {
          Text(text = stringResource(id = R.string.done))
        }
        OutlinedButton(onClick = { viewModel.cancelStickerPlacement() }) {
          Text(text = stringResource(id = R.string.cancel))
        }
      }
    } else {
      Button(
        enabled = uiState.effectsEnabled && uiState.effectsChanged,
        onClick = { viewModel.applyEffects() },
      ) {
        Text(text = stringResource(id = R.string.apply_effects))
      }
    }

    EffectControlsList(viewModel, uiState)
  }

  @Composable
  private fun EffectControlsList(viewModel: EffectViewModel, uiState: EffectUiState) {
    Box {
      EffectItems(viewModel, uiState)
      if (uiState.stickerPlacement is StickerPlacement.Placing) {
        // Scrim the list during placement: the mode boundary is visible and the controls behind
        // it can't be interacted with until Done or Cancel.
        Box(
          Modifier.matchParentSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  awaitPointerEvent().changes.forEach { it.consume() }
                }
              }
            }
        )
      }
    }
  }

  @Composable
  private fun EffectItems(viewModel: EffectViewModel, uiState: EffectUiState) {
    LazyColumn(Modifier.padding(vertical = dimensionResource(id = R.dimen.small_padding))) {
      item {
        EffectItem(
          name = stringResource(id = R.string.contrast),
          enabled = uiState.effectsEnabled,
          checked = uiState.contrastChecked,
          onCheckedChange = { checked -> viewModel.updateContrastChecked(checked) },
        ) {
          Row {
            Text(
              text = "%.2f".format(uiState.contrastValue),
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.padding(dimensionResource(id = R.dimen.large_padding)).weight(1f),
            )
            Slider(
              value = uiState.contrastValue,
              onValueChange = { viewModel.updateContrast(it) },
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
          name = stringResource(R.string.sticker_overlay),
          enabled = uiState.effectsEnabled && uiState.stickerAssetsLoaded,
          checked = uiState.stickerOverlayChecked,
          onCheckedChange = { checked -> viewModel.updateStickerChecked(checked) },
        ) {
          val createSticker =
            rememberLauncherForActivityResult(CreateStickerContract()) { stickerId ->
              if (stickerId != null) {
                viewModel.onStickerCreated(stickerId)
              }
            }
          Column {
            val selectedAsset =
              uiState.stickerAssets.find { it.id == uiState.selectedStickerAssetId }
                ?: uiState.stickerAssets.firstOrNull()
            if (selectedAsset != null) {
              GenericExposedDropdownMenu(
                label = stringResource(R.string.sticker_asset),
                selectedValue = selectedAsset,
                options = uiState.stickerAssets,
                onOptionSelected = { viewModel.updateSelectedStickerAsset(it.id) },
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(bottom = dimensionResource(R.dimen.large_padding)),
                itemLabelProvider = { it.name },
              )
            }
            val selectedAssetIsAnimated = selectedAsset is StickerAsset.Animated
            GenericExposedDropdownMenu(
              label = stringResource(R.string.sticker_animation),
              selectedValue = uiState.selectedStickerAnimation,
              options = StickerAnimation.entries,
              onOptionSelected = { viewModel.updateSelectedStickerAnimation(it) },
              modifier = Modifier.fillMaxWidth(),
              enabled = !selectedAssetIsAnimated,
              itemLabelProvider = { stringResource(it.labelRes()) },
            )
            if (selectedAssetIsAnimated) {
              Text(
                text = stringResource(R.string.sticker_animation_recorded_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.small_padding)),
              )
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.large_padding)))
            Row(
              horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.large_padding))
            ) {
              Button(
                enabled = uiState.stickerPlacement is StickerPlacement.Inactive,
                onClick = { viewModel.startStickerPlacement() },
              ) {
                Text(text = stringResource(R.string.place_sticker))
              }
              OutlinedButton(
                enabled = uiState.stickerPlacement is StickerPlacement.Inactive,
                onClick = { createSticker.launch(viewModel.currentMediaUri()) },
              ) {
                Text(text = stringResource(R.string.create_custom_sticker))
              }
            }
            if (uiState.placedStickers.isNotEmpty()) {
              Text(
                text = stringResource(R.string.placed_stickers),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.large_padding)),
              )
              uiState.placedStickers.forEach { sticker ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = sticker.assetName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                  )
                  OutlinedButton(
                    enabled = uiState.stickerPlacement is StickerPlacement.Inactive,
                    onClick = { viewModel.editPlacedSticker(sticker.id) },
                  ) {
                    Text(text = stringResource(R.string.edit))
                  }
                  IconButton(
                    enabled = uiState.stickerPlacement is StickerPlacement.Inactive,
                    onClick = { viewModel.removePlacedSticker(sticker.id) },
                  ) {
                    Icon(
                      imageVector = Icons.TwoTone.Delete,
                      contentDescription = stringResource(R.string.delete_sticker),
                    )
                  }
                }
              }
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
            OutlinedTextField(
              value = uiState.textOverlayText ?: "",
              onValueChange = { viewModel.updateText(it.ifEmpty { null }) },
              label = { Text(stringResource(R.string.text)) },
              singleLine = true,
              modifier =
                Modifier.fillMaxWidth().padding(bottom = dimensionResource(R.dimen.large_padding)),
            )
            Row {
              ColorsDropDownMenu(uiState.textOverlayColor) { color ->
                viewModel.updateTextColor(color)
              }
            }
            Row {
              Text(
                text = stringResource(R.string.alpha) + " = %.2f".format(uiState.textOverlayAlpha),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                  Modifier.padding(dimensionResource(id = R.dimen.large_padding)).weight(1f),
              )
              Slider(
                value = uiState.textOverlayAlpha,
                onValueChange = { newAlphaValue ->
                  val newRoundedAlphaValue = "%.2f".format(Locale.ROOT, newAlphaValue).toFloat()
                  viewModel.updateTextAlpha(newRoundedAlphaValue)
                },
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

private fun StickerAnimation.labelRes(): Int =
  when (this) {
    StickerAnimation.NONE -> R.string.sticker_animation_none
    StickerAnimation.ROCK -> R.string.sticker_animation_rock
    StickerAnimation.PULSE -> R.string.sticker_animation_pulse
    StickerAnimation.INTENSIFY -> R.string.sticker_animation_intensify
  }
