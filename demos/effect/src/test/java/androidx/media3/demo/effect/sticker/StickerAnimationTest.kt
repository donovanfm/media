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

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [StickerAnimation] modulation math. */
@RunWith(JUnit4::class)
class StickerAnimationTest {

  private val tolerance = 1e-4f

  @Test
  fun none_isIdentityAtAnyTime() {
    for (elapsedUs in longArrayOf(0, 123_456, 10_000_000, -500_000)) {
      assertThat(StickerAnimation.NONE.modulationAt(elapsedUs))
        .isEqualTo(AnimationModulation.IDENTITY)
    }
  }

  @Test
  fun rock_hardCutsBetweenAngles() {
    val first = StickerAnimation.ROCK.modulationAt(0)
    val stillFirst = StickerAnimation.ROCK.modulationAt(ROCK_HALF_PERIOD_US - 1)
    val second = StickerAnimation.ROCK.modulationAt(ROCK_HALF_PERIOD_US)
    val third = StickerAnimation.ROCK.modulationAt(2 * ROCK_HALF_PERIOD_US)

    assertThat(first.rotationDegrees).isWithin(tolerance).of(-ROCK_ANGLE_DEGREES)
    assertThat(stillFirst.rotationDegrees).isWithin(tolerance).of(-ROCK_ANGLE_DEGREES)
    assertThat(second.rotationDegrees).isWithin(tolerance).of(ROCK_ANGLE_DEGREES)
    assertThat(third.rotationDegrees).isWithin(tolerance).of(-ROCK_ANGLE_DEGREES)
    // Only rotation is modulated.
    assertThat(first.scaleFactor).isWithin(tolerance).of(1f)
    assertThat(first.anchorOffsetX).isWithin(tolerance).of(0f)
  }

  @Test
  fun rock_negativeElapsed_staysInCycle() {
    // floorDiv keeps pre-baseline times on the same two-state cycle instead of a third state.
    val beforeBaseline = StickerAnimation.ROCK.modulationAt(-1)

    assertThat(abs(beforeBaseline.rotationDegrees)).isWithin(tolerance).of(ROCK_ANGLE_DEGREES)
  }

  @Test
  fun pulse_oscillatesAroundIdentityWithinAmplitude() {
    assertThat(StickerAnimation.PULSE.modulationAt(0).scaleFactor).isWithin(tolerance).of(1f)
    assertThat(StickerAnimation.PULSE.modulationAt(PULSE_PERIOD_US / 4).scaleFactor)
      .isWithin(tolerance)
      .of(1f + PULSE_AMPLITUDE)
    assertThat(StickerAnimation.PULSE.modulationAt(3 * PULSE_PERIOD_US / 4).scaleFactor)
      .isWithin(tolerance)
      .of(1f - PULSE_AMPLITUDE)
    for (elapsedUs in 0 until PULSE_PERIOD_US step 50_000) {
      val scale = StickerAnimation.PULSE.modulationAt(elapsedUs).scaleFactor
      assertThat(scale).isAtLeast(1f - PULSE_AMPLITUDE - tolerance)
      assertThat(scale).isAtMost(1f + PULSE_AMPLITUDE + tolerance)
    }
  }

  @Test
  fun intensify_isDeterministicBoundedAndVaries() {
    val modulations =
      (0 until 20).map { StickerAnimation.INTENSIFY.modulationAt(it * JITTER_INTERVAL_US) }

    // Deterministic: the same timestamp always jitters the same way (stable across seeks).
    assertThat(StickerAnimation.INTENSIFY.modulationAt(5 * JITTER_INTERVAL_US))
      .isEqualTo(modulations[5])
    // Bounded by the amplitude.
    for (modulation in modulations) {
      assertThat(abs(modulation.anchorOffsetX)).isAtMost(JITTER_AMPLITUDE + tolerance)
      assertThat(abs(modulation.anchorOffsetY)).isAtMost(JITTER_AMPLITUDE + tolerance)
    }
    // Actually jumps around: not all buckets land on the same offset.
    assertThat(modulations.distinct().size).isGreaterThan(10)
  }

  @Test
  fun intensify_holdsWithinOneBucket() {
    val bucketStart = StickerAnimation.INTENSIFY.modulationAt(7 * JITTER_INTERVAL_US)
    val bucketEnd = StickerAnimation.INTENSIFY.modulationAt(8 * JITTER_INTERVAL_US - 1)

    assertThat(bucketEnd).isEqualTo(bucketStart)
  }
}
