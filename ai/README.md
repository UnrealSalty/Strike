# Strike AI detector

This folder publishes the detector used by Strike, its bundled model, and the
small source declarations it needs to compile. `Yolo.kt` and `yolo26n.tflite`
are unchanged copies from source revision
`92f37d9b4bdefad509129ba7505d38787e29a880`. File hashes and the origins of the
support excerpts are recorded in [source-manifest.json](source-manifest.json).

This is a source snapshot built as an Android library. It is not a second app
or a separate detection service. The released Strike APK currently loads the
model inside its recorder process. Publishing this folder does not change that
architecture or establish that it is all the source required for the APK's
license obligations. See [LICENSE](LICENSE), [NOTICE](NOTICE),
[Ultralytics' licensing terms](https://www.ultralytics.com/license), and its
[deployment guidance](https://github.com/orgs/ultralytics/discussions/2127).

## Included files

| File | Purpose |
| --- | --- |
| `src/main/java/com/strike/surveillance/Yolo.kt` | Original model loading, input preparation, inference and detection decoding. |
| `src/main/assets/models/yolo26n.tflite` | The exact model bundled with this source revision. |
| `src/main/java/com/strike/camera/Mosaic.kt` | Original RGBA frame declaration extracted from `FrameSink.kt`. |
| `src/main/java/com/strike/daemon/DaemonLog.kt` | Original stdout logger object extracted from `DaemonLog.kt`. |
| `src/main/java/com/strike/surveillance/Kinds.kt` | Original person and vehicle constants extracted from `EventStore.kt`. |
| `build.gradle.kts` | Android library build with TensorFlow Lite 2.14.0. |
| `check-model.py` | Optional host check of model hash, tensors and one blank-image inference. |

The support files preserve the extracted declarations; they do not simulate a
camera, replace the logger, or invent detections. Their new filenames and
package headers let the detector compile without copying unrelated capture,
recording or event-storage code.

## How detection works

1. Strike supplies a 640 by 480 RGBA image containing the four camera views in
   a two-by-two layout. Camera capture itself is outside this snapshot.
2. The detector centers it in a 640 by 640 RGB input, pads the unused area with
   `114/255`, and scales image color values to `[0, 1]`.
3. TensorFlow Lite runs the model on the CPU with two threads. The input is
   FLOAT32 `[1, 640, 640, 3]`; the bundled model returns FLOAT32 `[1, 300, 6]`.
4. Each output row contains `x1, y1, x2, y2, confidence, class`. The adapter
   reads these decoded rows; it does not run an additional non-maximum
   suppression pass.
5. Scores below `0.25` are ignored. COCO class `0` becomes `person`; classes
   `2`, `5` and `7` become `vehicle` (car, bus and truck).
6. The caller's minimum-size setting is applied relative to one camera view.
   Box coordinates are returned in the original mosaic's coordinate space.

Strike separately decides when motion merits inference and whether a sighting
should trigger an event. Those application policies are not implemented by this
library. Matching this detector's tensor format alone does not establish
accuracy on another model or camera.

## Build the Android library

Requires JDK 21, Android SDK Platform 36, and network access for the pinned
Gradle and Maven dependencies. Set `JAVA_HOME` and `ANDROID_HOME` for your own
installation; do not commit a machine-specific `local.properties` file.

From this folder on Windows:

```powershell
.\gradlew.bat assembleRelease --console=plain
```

On macOS or Linux:

```sh
sh ./gradlew assembleRelease --console=plain
```

The result is `build/outputs/aar/strike-ai-release.aar`. An AAR is a library,
not an installable APK. It includes the compiled detector and model asset; its
TensorFlow Lite dependency must also be included by an application consuming a
standalone AAR. The loader expects the model at
`assets/models/yolo26n.tflite` inside the application's APK, and TensorFlow
Lite's native library must be available to that process. No camera permissions,
vehicle SDK, live stream or recorder are supplied here.

## Optional model check on a computer

The check was written for Python 3.13 with TensorFlow 2.21.0 and NumPy. It uses
the host interpreter rather than the Android runtime dependency above.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install tensorflow==2.21.0 numpy
.\.venv\Scripts\python.exe check-model.py
```

It verifies the exact model checksum and tensor shapes, then runs inference on
a blank image. Passing it shows that the graph can load and run in that host
environment. It does not test the Kotlin adapter, real people or vehicles,
inference speed on a car, or recording and reboot recovery.

## Model provenance

The published model's SHA-256 is
`fc6755446dcb17a01d926420c1b6becc69e58b60b8371d3b802054a401c16602`.
It is copied from the source checkout's existing asset. The original training
checkpoint and export script are not recorded in that checkout, so this folder
does not claim that a new export can reproduce the same bytes. The manifest
records that gap rather than substituting a differently exported model.

The model's embedded metadata names TensorFlow 2.19.0 as its converter, a
minimum runtime version of 2.9.0, and post-training dynamic-range quantization.
FLOAT32 describes its input and output tensors, not every internal weight.
Those metadata fields do not supply the original checkpoint or export command.

The source and excerpts in this folder are published under AGPL-3.0 as described
in [NOTICE](NOTICE). The model retains its upstream terms. TensorFlow Lite and
Gradle retain their Apache-2.0 terms; see
[third_party/APACHE-2.0.txt](third_party/APACHE-2.0.txt).
