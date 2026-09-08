# Contributing to Strike

Strike focuses on recording, parked surveillance, and the dashboard used to
control them. See the [README](README.md#compatibility) for hardware requirements
and tested configurations.

## Build

Use JDK 17 or 21, Android SDK Platform 36, Build Tools 35.0.0, Android NDK
27.0.12077973, and CMake 3.22.1. The Gradle wrapper is included; Android Studio
is optional. These build tools follow the
[AGP 8.13 requirements](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

Set `JAVA_HOME` to your JDK and provide the SDK location through `ANDROID_HOME`
or an untracked `local.properties` file:

```properties
sdk.dir=C:/path/to/Android/Sdk
```

From the project root in PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Java and Kotlin bytecode target Java 11; Gradle itself needs the newer JDK above.

Android Studio's Run button builds for the selected device: ARM64 for the car,
x86-64 for an Intel/AMD emulator. Command-line builds default to ARM64. To build
for an x86-64 emulator from PowerShell:

```powershell
.\gradlew.bat assembleDebug "-Pandroid.injected.build.abi=x86_64,arm64-v8a"
```

The device-targeted command writes
`app/build/intermediates/apk/debug/app-debug.apk`; the default car command writes
`app/build/outputs/apk/debug/app-debug.apk`. Each APK contains only its selected
architecture. Release builds remain ARM64-only. Camera capture and vehicle
signals still require BYD hardware.

`targetSdk` remains 25 for the existing head-unit integration, while `minSdk` is
28. Lint's `ExpiredTargetSdkVersion` check is disabled because Strike is
distributed as a sideloaded APK. Changing these SDK values requires testing the
full parked recording lifecycle.

## Keep changes focused

- One fix or feature per change. Explain the problem, resulting behavior, and
  verification in the pull request.
- Discuss new integrations, dependencies, and vehicle support before implementing
  them. A firmware or layout difference needs evidence from that device.
- Preserve existing working capture and storage behavior. Keep compatibility work
  separate from formatting or comment cleanup.
- Describe compatibility as tested only when it has been checked on the hardware.
  A successful build or emulator run is not a vehicle test.

## Capture and storage

- `CameraSource` is the only camera owner. Share its frames through `FrameBus`;
  a feature must not open another camera session.
- Capture, encoding, and detection run in `CameraDaemon`. Keep UI and network
  work from blocking frame delivery or the supervisor.
- Preserve thread ownership for EGL, encoders, muxers, and inference. Account for
  simultaneous HTTP requests and ignition transitions.
- Use `ClipWriter` to finalize MP4 files before exposing them in a library.
  Never let retention remove a file still being written.
- Keep recording and surveillance storage budgets separate. Preserve the selected
  removable-storage path while waiting for a remount.
- Treat unavailable ignition, lock, or gear readings as unknown. Never invent a
  value or use a stale reading to arm the deterrent.
- Keep vehicle telemetry read-only. Existing camera power and display handling
  does not justify adding door, climate, or driving controls.

## Code and comments

- Use the existing solution before adding another pattern. Avoid wrappers with
  no behavior, speculative interfaces, and abstractions for hypothetical uses.
- Name values for what they represent. Include units where needed, such as
  `durationMs` and `budgetBytes`.
- Keep functions focused. Remove unused code and imports; do not retain a second
  abandoned implementation or commented-out code.
- Add a comment only for a constraint that names and code cannot explain:
  hardware behavior, thread ownership, protocol layout, or a storage invariant.
  Keep it to one or two lines. Preserve attribution and license notices.
- Put change history, investigation notes, and review explanations in the pull
  request, not source comments.
- Log failures where their meaning is known. Expected disconnects and unavailable
  firmware APIs should not flood the log, but a stopped recorder must be visible.
- Make performance claims from measurements. State the device, workload, and
  comparison; fewer lines of code is not evidence of lower CPU or power use.

## Web UI

All screens live in `app/src/main/assets/web/`. Use plain HTML, CSS, and JavaScript
with the existing components and tokens. No framework, bundler, CDN, or remote
fonts are needed.

Keep data and controls on HTTP so the phone and head unit use the same behavior.
Use English, the existing dark theme, and touch targets of at least 48 px.

The head unit has an old WebView. Use constructions that work on Chrome 58 until
a newer capability is demonstrated on the device. Avoid flex `gap`, `aspect-ratio`,
`:has()`, optional chaining, and `??`. Reuse the existing loading, empty, error,
and confirmation states.

## Verify the change

Follow the [build setup](#build), then run from the repository root:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

For a specific test class:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.strike.recording.RetentionTest"
```

For changes to the browser's H.264/MP4 handling, also run the existing SPS check:

```powershell
node tools/spscheck.js
```

Node is needed only for this developer check; it is not part of the app build or
runtime. Add tests for meaningful behavior, particularly clip finalization,
retention, restart limits, and ignition ordering. Avoid tests that only match
source text or repeat the implementation.

For UI changes, inspect the actual pages in a browser at phone and head-unit
sizes. A static preview can be served with Python:

```powershell
python -m http.server 8091 --bind 127.0.0.1 --directory app/src/main/assets/web
```

This serves assets only; live data and controls require Strike's API or test
fixtures. Inspect screenshots and computed styles for layout changes, then check
old-WebView behavior on the head unit. Restart the app after installing an updated
APK if cached assets remain visible.

For capture or surveillance changes, test the affected behavior on the car:
ignition transitions, arming, repeated events, playable clip endings, and a working
stop control. Include storage loss or delayed camera startup when the change
touches those paths. Report what was run, its result, and anything not verified.

## Project layout

| Path | Responsibility |
| --- | --- |
| `app/src/main/java/com/strike/daemon/` | Shell launch, watchdog, ignition monitoring, and capture supervision |
| `app/src/main/java/com/strike/camera/` | One camera source, shared frame delivery, GPU crops, and Live encoding |
| `app/src/main/java/com/strike/recording/` | Encoding, muxing, clip storage, retention, and cabin audio |
| `app/src/main/java/com/strike/surveillance/` | Motion, YOLO detection, event metadata, and the red screen |
| `app/src/main/java/com/strike/server/` | HTTP dashboard, JSON APIs, and WebSocket delivery |
| `app/src/main/java/com/strike/vehicle/` | Read-only vehicle telemetry |
| `app/src/main/assets/web/` | Shared HTML, CSS, and JavaScript for the car and phone |
| `app/src/main/cpp/` | EGL bindings for camera hardware buffers |
| `app/src/test/` | JVM tests |

The app hosts the web server. A separate `app_process` daemon runs as shell UID
2000 and owns capture, recording, and surveillance. `CameraSource` opens the
hardware; `FrameBus` shares it among consumers. Live starts its encoder only while
a browser is watching. Vehicle telemetry is read-only; parked capture separately
manages the camera power rails and deterrent display.

## Cloudflare connector

The APK includes cloudflared 2026.8.3 for its target architecture. To rebuild it,
install Go and the Android NDK listed above, then run
`.\tools\build-cloudflared.ps1` from PowerShell. Add `-Abi x86_64` for the emulator
connector. The script pins and verifies the upstream source, selects Go 1.26.6,
and adds the Android hooks in `tools/cloudflared/`: the parent-exit guard and
DNS resolution using the active Android network. Normal APK builds use the
bundled binary. Its [license notices](app/src/main/assets/cloudflared-notices.txt)
are included in the APK.

The token stays in private app storage and is never returned to the browser.
Cloudflared is a separate process that uses the existing HTTP server; it does
not own a camera or encoder. A foreground service keeps the app available while
remote access is enabled. When disabled, there is no tunnel process or supervisor
thread. The connector exits if the app process dies.

## Publishing a release

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build a signed release APK with the original keystore.
3. Create a GitHub release with a matching tag, such as `v0.2` for version `0.2`.
4. Attach the signed APK as **Strike.apk** and publish the release as the latest
   stable release. Drafts and prereleases are not offered by the checker.

Keep the signing key backed up outside the repository. The updater checks the
APK checksum, package, signing key, version, Android requirement, and processor
architecture before installation. A debug build cannot update to an APK signed
with a different key.

## Reference code and reports

[Overdrive](https://github.com/yash-srivastava/Overdrive-release) is a reference
for BYD hardware integration. When using it to support a change, identify the
relevant implementation and what it confirms. Keep unrelated
architecture, dependencies, and UI out of Strike.

Bug reports should include the vehicle and firmware, Strike build, steps to
reproduce, and expected versus actual behavior. Add relevant logs or screenshots
when available; remove credentials, precise locations, and private footage before
sharing them. Keep signing keys, tokens, `local.properties`, and generated build
files out of contributions.
