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

import kotlin.math.PI
import kotlin.math.sin

/**
 * Preset placement animations for stickers.
 *
 * Unlike recorded animated stickers (which swap bitmap frames over time), these animate the
 * overlay's [androidx.media3.common.OverlaySettings] — rotation, scale, and anchor position —
 * while the texture stays constant. That makes them free at render time (no per-frame texture
 * uploads) and perfectly smooth at any video frame rate. The modulation math is pure and
 * unit-testable; the overlay class assembles it into settings.
 */
internal enum class StickerAnimation {
  /** No preset animation; the sticker renders at its placed position. */
  NONE,
  /** Rocks between -15 and +15 degrees with a hard cut, like a metronome meme. */
  ROCK,
  /** Smoothly grows and shrinks around the placed size. */
  PULSE,
  /** Jumps rapidly over very small distances, imitating intense anticipation. */
  INTENSIFY,
}

/** How a preset modifies the placed overlay settings at one instant. */
internal data class AnimationModulation(
  val rotationDegrees: Float = 0f,
  val scaleFactor: Float = 1f,
  val anchorOffsetX: Float = 0f,
  val anchorOffsetY: Float = 0f,
) {
  companion object {
    val IDENTITY = AnimationModulation()
  }
}

/**
 * Returns the modulation of this preset at [elapsedUs] since the overlay's first frame. Handles
 * negative elapsed times (seeks behind the baseline) by looping like positive ones.
 */
internal fun StickerAnimation.modulationAt(elapsedUs: Long): AnimationModulation {
  return when (this) {
    StickerAnimation.NONE -> AnimationModulation.IDENTITY
    StickerAnimation.ROCK ->
      AnimationModulation(
        rotationDegrees =
          if (Math.floorDiv(elapsedUs, ROCK_HALF_PERIOD_US) % 2 == 0L) {
            -ROCK_ANGLE_DEGREES
          } else {
            ROCK_ANGLE_DEGREES
          }
      )
    StickerAnimation.PULSE ->
      AnimationModulation(
        scaleFactor =
          1f + PULSE_AMPLITUDE * sin(2.0 * PI * elapsedUs / PULSE_PERIOD_US).toFloat()
      )
    StickerAnimation.INTENSIFY -> {
      // A new deterministic pseudo-random offset every bucket: hashing the bucket index keeps the
      // jitter stable for a given timestamp (consistent across seeks and preview/export), unlike
      // Random. Fibonacci hashing spreads consecutive buckets well.
      val bucket = Math.floorDiv(elapsedUs, JITTER_INTERVAL_US)
      val hash = bucket * -0x61c8864680b583ebL
      AnimationModulation(
        anchorOffsetX = (((hash ushr 16) and 0xFFFF).toFloat() / 32767.5f - 1f) * JITTER_AMPLITUDE,
        anchorOffsetY = (((hash ushr 32) and 0xFFFF).toFloat() / 32767.5f - 1f) * JITTER_AMPLITUDE,
      )
    }
  }
}

/** Hard-cut interval for [StickerAnimation.ROCK]: the sticker flips sides every 400ms. */
internal const val ROCK_HALF_PERIOD_US = 400_000L
internal const val ROCK_ANGLE_DEGREES = 15f

/** Full grow-and-shrink cycle length for [StickerAnimation.PULSE]. */
internal const val PULSE_PERIOD_US = 1_000_000L
internal const val PULSE_AMPLITUDE = 0.15f

/** New jitter position every 50ms for [StickerAnimation.INTENSIFY]. */
internal const val JITTER_INTERVAL_US = 50_000L
/** Jitter amplitude in background-frame anchor units ([-1, 1] spans the video). */
internal const val JITTER_AMPLITUDE = 0.015f
