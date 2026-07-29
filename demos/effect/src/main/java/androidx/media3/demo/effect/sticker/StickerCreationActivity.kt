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

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.effect.R
import androidx.media3.demo.effect.sticker.VideoCoordinateMapper.NormalizedPoint
import kotlin.math.roundToInt

/**
 * Full-screen flow for creating a custom sticker from a video.
 *
 * The video plays in a plain [TextureView] (so frames can be read back with
 * [TextureView.getBitmap]) sized to the video's aspect ratio. Long-pressing an object pauses
 * playback, runs MediaPipe interactive segmentation at the pressed point, and previews the mask;
 * the cutout can then be named and saved. Launch via [CreateStickerContract]; the caller receives
 * the saved sticker's id.
 */
@OptIn(UnstableApi::class)
class StickerCreationActivity : ComponentActivity() {

  private val viewModel: StickerCreationViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) {
      val uriExtra = intent.getStringExtra(CreateStickerContract.EXTRA_MEDIA_URI)
      viewModel.setMediaUri(uriExtra?.let { Uri.parse(it) })
    }
    setContent { StickerCreationScreen(viewModel) }
  }

  @Composable
  private fun StickerCreationScreen(viewModel: StickerCreationViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val savedStickerId by viewModel.savedStickerId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
      uiState.errorMessage?.let { message ->
        snackbarHostState.showSnackbar(message)
        viewModel.clearErrorMessage()
      }
    }

    LaunchedEffect(savedStickerId) {
      savedStickerId?.let { id ->
        setResult(
          RESULT_OK,
          Intent().putExtra(CreateStickerContract.EXTRA_STICKER_ID, id),
        )
        finish()
      }
    }

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
      Column(
        modifier = Modifier.fillMaxWidth().padding(paddingValues),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = stringResource(R.string.sticker_creation_title),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(dimensionResource(R.dimen.large_padding)),
        )
        ModeSelector(viewModel, uiState)
        when (uiState.phase) {
          CreationPhase.AwaitingVideo -> VideoChooser(viewModel)
          else -> {
            VideoSurface(viewModel, uiState)
            CreationControls(viewModel, uiState)
          }
        }
      }
    }
  }

  @Composable
  private fun ModeSelector(viewModel: StickerCreationViewModel, uiState: StickerCreationUiState) {
    val recordingOrSaving =
      uiState.phase is CreationPhase.Recording || uiState.phase == CreationPhase.Saving
    SingleChoiceSegmentedButtonRow {
      StickerMode.entries.forEachIndexed { index, mode ->
        SegmentedButton(
          selected = uiState.mode == mode,
          onClick = { viewModel.setMode(mode) },
          enabled = !recordingOrSaving,
          shape =
            SegmentedButtonDefaults.itemShape(index = index, count = StickerMode.entries.size),
        ) {
          Text(
            text =
              stringResource(
                when (mode) {
                  StickerMode.STATIC -> R.string.sticker_mode_static
                  StickerMode.ANIMATED -> R.string.sticker_mode_animated
                }
              )
          )
        }
      }
    }
  }

  @Composable
  private fun VideoChooser(viewModel: StickerCreationViewModel) {
    val pickVideo =
      rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
          viewModel.setMediaUri(uri)
        }
      }
    Button(
      onClick = {
        pickVideo.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly))
      },
      modifier = Modifier.padding(dimensionResource(R.dimen.large_padding)),
    ) {
      Text(text = stringResource(R.string.sticker_choose_video))
    }
  }

  @Composable
  private fun VideoSurface(viewModel: StickerCreationViewModel, uiState: StickerCreationUiState) {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(dimensionResource(R.dimen.android_view_height))
          .padding(all = dimensionResource(id = R.dimen.regular_padding))
          .clip(RoundedCornerShape(12.dp))
          .background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      if (uiState.videoAspectRatio > 0f) {
        // Sizing the TextureView to the video's aspect ratio keeps the surface undistorted and
        // free of letterbox bars; taps map to normalized coordinates via the exact-fit path of
        // VideoCoordinateMapper.
        Box(modifier = Modifier.aspectRatio(uiState.videoAspectRatio)) {
          AndroidView(
            factory = { context -> TextureView(context) },
            update = { textureView ->
              viewModel.player.setVideoTextureView(textureView)
              viewModel.attachFrameSource { reuse ->
                if (textureView.isAvailable) {
                  textureView.getBitmap(reuse ?: createCaptureBitmap(uiState.videoAspectRatio))
                } else {
                  null
                }
              }
            },
            onRelease = { textureView ->
              viewModel.attachFrameSource(null)
              viewModel.player.clearVideoTextureView(textureView)
            },
            modifier =
              Modifier.fillMaxSize().pointerInput(uiState.mode, uiState.videoAspectRatio) {
                when (uiState.mode) {
                  StickerMode.STATIC ->
                    detectTapGestures(
                      onLongPress = { offset ->
                        mapToVideoPoint(offset)?.let { viewModel.createStaticSticker(it) }
                      }
                    )
                  StickerMode.ANIMATED ->
                    awaitEachGesture {
                      val down = awaitFirstDown()
                      val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                      val startPoint =
                        mapToVideoPoint(longPress.position) ?: return@awaitEachGesture
                      viewModel.startRecording(startPoint)
                      try {
                        drag(longPress.id) { change ->
                          mapToVideoPoint(change.position)?.let {
                            viewModel.updateFingerPosition(it)
                          }
                          change.consume()
                        }
                      } finally {
                        // Finger lifted or the gesture was cancelled; drain the capture loop.
                        viewModel.stopRecording()
                      }
                    }
                }
              },
          )
          MaskOverlay(uiState.phase)
        }
      } else {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
      }
    }
  }

  @Composable
  private fun MaskOverlay(phase: CreationPhase) {
    when (phase) {
      CreationPhase.Segmenting ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      is CreationPhase.StaticPreview ->
        Image(
          bitmap = phase.maskPreview.asImageBitmap(),
          contentDescription = stringResource(R.string.sticker_mask_preview),
          contentScale = ContentScale.FillBounds,
          modifier = Modifier.fillMaxSize(),
        )
      is CreationPhase.Recording ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(dimensionResource(R.dimen.regular_padding)),
        ) {
          Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Red))
          Text(
            text = stringResource(R.string.sticker_recording_status, phase.frameCount),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = dimensionResource(R.dimen.small_padding)),
          )
        }
      else -> {}
    }
  }

  /** A looping preview of a recorded animation, driven by the same [FrameTimeline] as playback. */
  @Composable
  private fun AnimatedStickerPreview(animation: AnimatedSticker, modifier: Modifier = Modifier) {
    val timeline =
      remember(animation) { FrameTimeline(animation.timestampsUs, animation.durationUs) }
    var frameIndex by remember(animation) { mutableIntStateOf(0) }
    LaunchedEffect(animation) {
      val startNanos = withFrameNanos { it }
      while (true) {
        withFrameNanos { now -> frameIndex = timeline.frameIndexAt((now - startNanos) / 1000) }
      }
    }
    Image(
      bitmap = animation.frames[frameIndex].asImageBitmap(),
      contentDescription = stringResource(R.string.sticker_cutout_preview),
      modifier = modifier,
    )
  }

  @Composable
  private fun CreationControls(
    viewModel: StickerCreationViewModel,
    uiState: StickerCreationUiState,
  ) {
    when (val phase = uiState.phase) {
      CreationPhase.Playing -> {
        Text(
          text =
            stringResource(
              when (uiState.mode) {
                StickerMode.STATIC -> R.string.sticker_hint_long_press
                StickerMode.ANIMATED -> R.string.sticker_hint_hold_to_record
              }
            ),
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(dimensionResource(R.dimen.large_padding)),
        )
        if (!uiState.segmenterReady) {
          CircularProgressIndicator()
        }
      }
      is CreationPhase.StaticPreview -> {
        Image(
          bitmap = phase.cutout.asImageBitmap(),
          contentDescription = stringResource(R.string.sticker_cutout_preview),
          modifier =
            Modifier.size(96.dp).padding(vertical = dimensionResource(R.dimen.small_padding)),
        )
        SavePanel(viewModel, uiState)
      }
      is CreationPhase.AnimatedPreview -> {
        AnimatedStickerPreview(
          animation = phase.animation,
          modifier =
            Modifier.size(96.dp).padding(vertical = dimensionResource(R.dimen.small_padding)),
        )
        SavePanel(viewModel, uiState)
      }
      CreationPhase.Saving -> CircularProgressIndicator()
      else -> {}
    }
  }

  @Composable
  private fun SavePanel(viewModel: StickerCreationViewModel, uiState: StickerCreationUiState) {
    OutlinedTextField(
      value = uiState.stickerName,
      onValueChange = { viewModel.setStickerName(it) },
      label = { Text(stringResource(R.string.sticker_name_label)) },
      singleLine = true,
      modifier =
        Modifier.fillMaxWidth().padding(horizontal = dimensionResource(R.dimen.large_padding)),
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.large_padding)),
      modifier = Modifier.padding(dimensionResource(R.dimen.large_padding)),
    ) {
      Button(onClick = { viewModel.saveSticker() }) {
        Text(text = stringResource(R.string.sticker_save))
      }
      OutlinedButton(onClick = { viewModel.discardPreview() }) {
        Text(text = stringResource(R.string.sticker_retry))
      }
    }
  }

  /**
   * Maps a pointer position on the aspect-sized video surface to normalized video coordinates.
   * The surface has no letterbox bars, so its own dimensions act as the video dimensions for the
   * exact-fit mapping.
   */
  private fun PointerInputScope.mapToVideoPoint(position: Offset): NormalizedPoint? =
    VideoCoordinateMapper.viewToNormalizedVideo(
      viewWidth = size.width.toFloat(),
      viewHeight = size.height.toFloat(),
      videoWidth = size.width,
      videoHeight = size.height,
      tapX = position.x,
      tapY = position.y,
    )

  /** Allocates a capture bitmap capped at [StickerCreationViewModel.CAPTURE_MAX_DIMENSION]. */
  private fun createCaptureBitmap(aspectRatio: Float): Bitmap {
    val maxDimension = StickerCreationViewModel.CAPTURE_MAX_DIMENSION
    val (width, height) =
      if (aspectRatio >= 1f) {
        maxDimension to (maxDimension / aspectRatio).roundToInt().coerceAtLeast(1)
      } else {
        (maxDimension * aspectRatio).roundToInt().coerceAtLeast(1) to maxDimension
      }
    return createBitmap(width, height)
  }
}
