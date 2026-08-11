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

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * A [DefaultRenderersFactory] whose video renderer enables the effects pipeline's replayable
 * frame cache.
 *
 * This demo applies effects live, including while playback is paused. A paused player renders no
 * new frames, so a changed pipeline is only visible once the current frame is re-rendered through
 * it — which is what passing [androidx.media3.common.VideoFrameProcessor.REDRAW] to
 * [androidx.media3.exoplayer.ExoPlayer.setVideoEffects] does. Redrawing needs the frame cache,
 * which is off by default (it costs extra memory and GPU work) and is not yet exposed through a
 * public player API, so this factory opts in by overriding the video renderer's
 * [PlaybackVideoGraphWrapper] creation. Once ExoPlayer exposes the setting directly this class
 * can be deleted.
 */
@OptIn(UnstableApi::class)
internal class RedrawEnabledRenderersFactory(context: Context) :
  DefaultRenderersFactory(context) {

  override fun buildVideoRenderers(
    context: Context,
    extensionRendererMode: Int,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    allowedVideoJoiningTimeMs: Long,
    out: ArrayList<Renderer>,
  ) {
    val builder =
      MediaCodecVideoRenderer.Builder(context)
        .setCodecAdapterFactory(codecAdapterFactory)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
    out.add(RedrawEnabledVideoRenderer(builder))
  }

  private class RedrawEnabledVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {
    override fun createPlaybackVideoGraphWrapper(
      context: Context,
      videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper =
      PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
        .setEnablePlaylistMode(true)
        .setEnableReplayableCache(true)
        .setClock(clock)
        .build()
  }
}
