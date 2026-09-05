# Effect demo

This app demonstrates how to use the [Effect][] API to modify videos. It uses
`setVideoEffects` method to add different effects to [ExoPlayer][].

Among the available effects, the sticker overlay lets you position an image
over the video by dragging and pinch-zooming it directly on the player, then
bakes it into playback as a `BitmapOverlay`.

Custom stickers can be created from the loaded video with MediaPipe
interactive segmentation: long-press an object on a paused frame for a static
sticker, or switch to animated mode and hold the object while the video plays —
frames are recorded (follow the object with your finger) until you lift, then
segmented in a short processing pass, and the result plays back as a looping
animated overlay. Recording samples frames at a fixed rate and defers
segmentation until after the gesture, so capture stays smooth regardless of
how fast the device runs the model.

See the [demos README](../README.md) for instructions on how to build and run
this demo.

## Notes

* This demo builds against `minSdk` 24, which the MediaPipe `tasks-vision`
  dependency also requires.
* The MediaPipe interactive segmentation model bundle
  (`interactive_segmentation.task`, ~30 MB) is downloaded automatically on
  first build by the `downloadSegmenterModel` Gradle task, verified against a
  pinned SHA-256, and cached in the build directory. Builds run with
  `--offline` or `-PskipStickerModelDownload` skip the download and still
  succeed; the app then disables custom sticker creation. To supply the model
  manually, download it from
  https://storage.googleapis.com/mediapipe-models/interactive_segmenter_v2/magic_touch/int8/1/interactive_segmentation.task
  and place it at
  `demos/effect/buildout/downloadedAssets/interactive_segmentation.task` (it
  is checksum-verified like a downloaded one). The legacy `magic_touch.tflite`
  only works with `InteractiveSegmenterLegacy`, not the tasks-vision 1.0 API
  this demo uses.
* The segmentation model is the [MediaPipe interactive segmenter "magic
  touch" model](https://ai.google.dev/edge/mediapipe/solutions/vision/interactive_segmenter),
  provided by Google and subject to the license terms on its model page.

[Effect]: https://github.com/androidx/media/tree/release/libraries/effect
[ExoPlayer]: https://github.com/androidx/media/tree/release/libraries/exoplayer
