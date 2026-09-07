# Strike

Dashcam and parked surveillance for BYD head units, with local recordings,
motion detection, and a live web dashboard for the car's screen and your phone.

## What it does

- Records while the car is on or only while driving, with rotating MP4 clips.
- Watches the parked car in Smart or Continuous mode.
- Shows all four camera views together, or an individual angle, in Live and playback.
- Keeps recordings and surveillance in separate libraries, with storage budgets,
  thumbnails, date filters, and event markers.
- Offers an optional red screen message for detected events and an optional
  PIN for dashboard access.
- Shows vehicle status, recorder controls, and local logs.

Footage stays on the selected internal, SD, or USB storage. Detection runs on the
head unit.

## Compatibility

Compatibility depends on the head unit's camera layout and firmware APIs.
The current build requires:

- Android 9 or newer, with a 64-bit ARM system (`arm64-v8a`).
- BYD's `AVMCamera` interface and a horizontal strip containing four camera views.
- Local ADB access on port 5555, authorized on the head unit.
- Working BYD ignition readings for automatic parked operation. Lock readings
  allow arming when the car locks; an unavailable lock reading has a timed fallback.

There is no model-name restriction, but the camera layout and firmware APIs
matter. Different camera layouts need additional handling, and the Qualcomm
AIS/QCarCam capture path used by some DiLink 5 units is not implemented. Installing
the APK on a phone or emulator does not provide access to the car cameras.

Testing so far has been on a BYD Atto 2. Other models and firmware versions
have not yet been verified.

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

`targetSdk` remains 25 for the existing head-unit integration, while `minSdk` is
28. The debug build emits an SDK warning; release assembly is currently blocked
by lint's `ExpiredTargetSdkVersion` check. Changing these values requires testing
the full parked recording lifecycle.

## Set up on the car

1. Install the debug APK on the head unit and open Strike.
2. Grant storage and microphone permissions. Microphone access is used only when
   cabin audio is enabled.
3. Enable the head unit's ADB access, then use **Daemons → Connect** and accept
   the debugging authorization prompt if one appears.
4. In **Recordings → Settings**, select a recording mode, storage location,
   budget, and clip length. Recording mode defaults to Off.
5. Turn on **Recorder** on the Daemons page.
6. In **Surveillance → Settings**, enable **Watch the car when it is off** and
   choose Smart or Continuous. Set its storage and budget separately.

To use a phone, connect it to a network that can reach the head unit and open
`http://<head-unit-ip>:8090/`. The app must be running to serve the dashboard.
Set a PIN through **Dashboard → Security** if other devices can reach it.
Without a PIN, the dashboard is accessible to any device that can connect to
that port. The current server uses HTTP and is intended for a trusted local network.

## PIN recovery

If you forget your PIN, select **Forgot PIN?** below the lock-screen keypad.
On a computer with an authorised ADB connection to the head unit, run:

```powershell
adb shell touch /data/local/tmp/.strike_pin_reset
```

Reopen or refresh Strike, then set a new PIN under **Dashboard → Security**.
This clears the PIN and failed-attempt lockout. Recordings and other settings
are preserved.

## Recording and surveillance

Drive clips rotate at the selected 2, 5, or 10 minute interval. H.264 and H.265,
quality, frame rate, and optional cabin audio are configurable. Hardware encoder
limits still apply.

Smart surveillance checks motion before running person and vehicle detection.
It builds a baseline of stationary vehicles to avoid treating them as new events.
If the detector cannot run, it logs the failure and falls back to motion-triggered
clips.

Each confirmed event extends recording by 20 seconds. Continued activity keeps
recording, rotating into another file at about two minutes per Smart clip. Clip
boundaries wait for an encoder keyframe. The red screen's duration is independent
of the recording tail.

Continuous surveillance records throughout the armed period, using the configured
clip length. Parked clips do not include cabin audio. Arming can follow ignition
off or locking; when lock state is unavailable, On lock falls back after 60 seconds
of confirmed parking. Unknown ignition state does not arm surveillance.

Files live under `Strike/clips` on the selected volume. Recording and surveillance
use distinct filename prefixes and budgets, even when they share a directory.
Retention removes the oldest finalized clips when a budget is exceeded. An open
clip keeps a temporary filename until its MP4 index is finalized; a power cut or
forced process kill can leave that clip unplayable.

Parked capture keeps the required camera and head-unit power rails awake, so it
uses power while armed. Runtime and battery consumption have not been measured
across vehicles.

## How it is organized

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

Overdrive is the reference for the BYD camera, SDK, storage, and parked-power
integration; it is not required to build Strike. Person and vehicle detection
uses the bundled `app/src/main/assets/models/yolo26n.tflite` model.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and verification rules.
