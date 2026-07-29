# Effect demo

This app demonstrates how to use the [Effect][] API to modify videos. It uses
`setVideoEffects` method to add different effects to [ExoPlayer][].

Among the available effects, the sticker overlay lets you position an image
over the video by dragging and pinch-zooming it directly on the player, then
bakes it into playback as a `BitmapOverlay`.

Custom stickers can be created from the loaded video with MediaPipe
interactive segmentation: long-press an object on a paused frame for a static
sticker, or switch to animated mode and hold the object while the video plays —
frames are recorded (follow the object with your finger) until you lift, and
the result plays back as a looping animated overlay.

See the [demos README](../README.md) for instructions on how to build and run
this demo.

## Notes

* This demo requires `minSdk` 24 (the rest of the repository builds against
  23) because of the MediaPipe `tasks-vision` dependency.
* The MediaPipe interactive segmentation model (`magic_touch.tflite`, ~6 MB)
  is downloaded automatically on first build by the `downloadSegmenterModel`
  Gradle task and cached in the build directory. When building offline,
  download it manually from
  https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/latest/magic_touch.tflite
  and place it at `demos/effect/buildout/downloadedAssets/magic_touch.tflite`.

[Effect]: https://github.com/androidx/media/tree/release/libraries/effect
[ExoPlayer]: https://github.com/androidx/media/tree/release/libraries/exoplayer
