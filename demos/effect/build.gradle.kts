// Copyright 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import java.security.MessageDigest

plugins {
  id("media3.android-application")
  alias(libs.plugins.kotlin.compose.compiler)
}

android {
  namespace = "androidx.media3.demo.effect"

  // TODO: b/502167525 - Remove this temporary JVM 11 override once commonConfig.kt
  // is migrated to Java 11 and the alpha25 dependency is reverted back to the BOM.
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  // TODO: b/520274937 - Move to commonConfig.kt
  defaultConfig { minSdk = 24 }

  buildTypes {
    getByName("release") {
      isShrinkResources = true
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  // The demo app isn't indexed, and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")

  buildFeatures { compose = true }

  testOptions.unitTests.all { test ->
    // Forward Robolectric configuration (e.g. -Drobolectric.offline=true and
    // -Drobolectric.dependency.dir=...) to test workers so tests can run without network access.
    System.getProperties().stringPropertyNames()
      .filter { it.startsWith("robolectric.") }
      .forEach { name -> test.systemProperty(name, System.getProperty(name)) }
  }

  // The MediaPipe segmentation model is downloaded at build time (see downloadSegmenterModel)
  // rather than checked into the repository. Resolved to a plain File because the SourceSet API
  // doesn't accept Provider instances; the task dependency is wired via preBuild below.
  sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("downloadedAssets").get().asFile)
}

/**
 * Downloads the MediaPipe interactive segmentation model into the build directory, where it is
 * picked up as an asset. The file is verified against a pinned SHA-256 (every build packages the
 * exact bytes the demo was tested with — including cached or hand-placed files, so a truncated or
 * stale model can't hide in the cache) and written via a temp file + atomic rename, so an
 * interrupted build never leaves a broken model behind.
 *
 * The download is skipped when Gradle runs with --offline or -PskipStickerModelDownload; the
 * build still succeeds and the app disables custom sticker creation when the asset is absent.
 */
val downloadSegmenterModel by
  tasks.registering {
    // The tasks-vision 1.0 InteractiveSegmenter requires the v2 .task bundle; the older
    // magic_touch.tflite only works with InteractiveSegmenterLegacy.
    val modelUrl =
      "https://storage.googleapis.com/mediapipe-models/interactive_segmenter_v2/magic_touch/int8/1/interactive_segmentation.task"
    val modelSha256 = "38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03"
    val outputFile = layout.buildDirectory.file("downloadedAssets/interactive_segmentation.task")
    val skipRequested =
      gradle.startParameter.isOffline ||
        providers.gradleProperty("skipStickerModelDownload").isPresent
    // Deliberately NOT registered as a task output: the model is a checksum-verified cache the
    // task manages itself. Registering it would let Gradle's stale-output cleanup delete it
    // whenever the task implementation changes, forcing a pointless 30MB re-download.
    doLast {
      fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
          }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
      }

      val target = outputFile.get().asFile
      if (target.exists()) {
        if (sha256(target) == modelSha256) {
          return@doLast
        }
        logger.warn("Cached segmentation model failed SHA-256 verification; re-downloading.")
        target.delete()
      }
      if (skipRequested) {
        logger.warn(
          "Skipping segmentation model download (offline build or -PskipStickerModelDownload); " +
            "custom sticker creation will be disabled in this build."
        )
        return@doLast
      }
      target.parentFile.mkdirs()
      val tempFile = File(target.parentFile, target.name + ".part")
      try {
        uri(modelUrl).toURL().openStream().use { input ->
          tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        val actualSha256 = sha256(tempFile)
        if (actualSha256 != modelSha256) {
          throw GradleException(
            "Downloaded segmentation model failed SHA-256 verification: expected $modelSha256, " +
              "got $actualSha256."
          )
        }
        if (!tempFile.renameTo(target)) {
          throw GradleException("Could not move ${tempFile.path} to ${target.path}.")
        }
      } catch (e: Exception) {
        tempFile.delete()
        if (e is GradleException) {
          throw e
        }
        throw GradleException(
          "Failed to download the MediaPipe segmentation model. If building offline, use " +
            "-PskipStickerModelDownload (custom sticker creation will be disabled) or download " +
            "$modelUrl manually and place it at ${target.path}.",
          e,
        )
      }
    }
  }

tasks.named("preBuild") { dependsOn(downloadSegmenterModel) }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(libs.material)

  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-ui-compose-material3"))
  // TODO(b/555710150): Revert this once our Gradle BOM is updated to a stable version that
  // includes the M3 API changes (ExposedDropdownMenu).
  implementation("androidx.compose.material3:material3:1.5.0-alpha26")
  implementation(project(":lib-effect"))
  implementation(project(":lib-effect-lottie"))

  implementation(libs.mediapipe.tasks.vision)

  // For detecting and debugging leaks only. LeakCanary is not needed for demo app to work.
  debugImplementation(libs.leakcanary.android)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.kotlinx.coroutines.test)
}
