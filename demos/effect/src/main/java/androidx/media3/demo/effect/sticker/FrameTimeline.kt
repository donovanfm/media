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

/**
 * Maps elapsed playback time to a frame index of a looping animated sticker.
 *
 * [timestampsUs] are the recorded frame timestamps rebased to start at 0 and strictly increasing;
 * [durationUs] is the loop length (greater than the last timestamp, so the final frame holds for
 * the remainder of the loop). Pure math, shared by the playback overlay and the creation screen's
 * preview, and unit-testable on the JVM.
 */
internal class FrameTimeline(private val timestampsUs: LongArray, val durationUs: Long) {

  init {
    require(timestampsUs.isNotEmpty()) { "At least one frame is required" }
    require(timestampsUs[0] == 0L) { "Timestamps must be rebased to start at 0" }
    for (i in 1 until timestampsUs.size) {
      require(timestampsUs[i] > timestampsUs[i - 1]) { "Timestamps must be strictly increasing" }
    }
    require(durationUs > timestampsUs.last()) { "Duration must extend past the last frame" }
  }

  val frameCount: Int
    get() = timestampsUs.size

  /**
   * Returns the index of the frame visible at [elapsedUs], looping over [durationUs]. Negative
   * elapsed times (e.g. after a seek before the overlay's baseline) wrap around the loop.
   */
  fun frameIndexAt(elapsedUs: Long): Int {
    val loopedUs = Math.floorMod(elapsedUs, durationUs)
    // Binary search for the largest index whose timestamp is <= loopedUs.
    var low = 0
    var high = timestampsUs.size - 1
    while (low < high) {
      val mid = (low + high + 1) ushr 1
      if (timestampsUs[mid] <= loopedUs) {
        low = mid
      } else {
        high = mid - 1
      }
    }
    return low
  }
}
