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

plugins {
  id("media3.android-application")
  alias(libs.plugins.kotlin.compose.compiler)
}

android {
  namespace = "androidx.media3.demo.effect"

  defaultConfig {
    // MediaPipe tasks-vision requires API 24. This overrides the repo-wide minSdk set by the
    // media3.android-application convention plugin for this demo only.
    minSdk = 24
  }

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
 * picked up as an asset. Cached across builds; if you need to build offline, download the file
 * manually from the URL below and place it at the output path.
 */
val downloadSegmenterModel by
  tasks.registering {
    val modelUrl =
      "https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/latest/magic_touch.tflite"
    val outputFile = layout.buildDirectory.file("downloadedAssets/magic_touch.tflite")
    outputs.file(outputFile)
    onlyIf { !outputFile.get().asFile.exists() }
    doLast {
      val target = outputFile.get().asFile
      target.parentFile.mkdirs()
      try {
        uri(modelUrl).toURL().openStream().use { input ->
          target.outputStream().use { output -> input.copyTo(output) }
        }
      } catch (e: Exception) {
        target.delete()
        throw GradleException(
          "Failed to download the MediaPipe segmentation model. If building offline, download " +
            "$modelUrl manually and place it at ${target.path}.",
          e,
        )
      }
    }
  }

tasks.named("preBuild") { dependsOn(downloadSegmenterModel) }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)

  implementation(libs.androidx.activity.compose)
  implementation(libs.material)

  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-ui-compose-material3"))
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
