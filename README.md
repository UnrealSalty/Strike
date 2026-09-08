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
  PIN for the in-car screen.
- Shows vehicle status, recorder controls, and local logs.
- Provides local network access and an optional Cloudflare tunnel with automatic
  start and stop based on ignition or door locking.

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

## Set up on the car

1. Install `Strike.apk` from [Releases](https://github.com/UnrealSalty/Strike/releases)
   on the head unit and open Strike. A local debug build also works for development.
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
Enter the browser access code shown in **Online** on the car's screen. Browser
access always requires this code, whether using the local address or a tunnel.
The optional **Dashboard → Security** PIN protects the APK only and does not
appear in browsers. Local access uses HTTP and is intended for a trusted network.

## Online

The **Online** tab shows the car's local addresses and controls an optional
Cloudflare tunnel. Remote access defaults to Off.

In the car, **Online → Browser access** lets you show, copy or regenerate the
access code. Each browser signs in once and keeps a session for 90 days, including
across app restarts. Regenerating the code signs out every browser and closes
existing browser Live streams and downloads. It does not change the car PIN.
Code management is available only on the car's screen after unlocking the app.

Strike generates a random 16-character code and stores it in private app storage.
The browser remembers a signed session in an HttpOnly cookie; the code is not
stored in localStorage or placed in the URL. Wrong attempts are rate-limited.
The server checks access before serving APIs, footage, thumbnails or Live, so a
direct clip link also requires a valid session.

To use your own domain:

1. Add the domain to Cloudflare and create a dedicated cloudflared tunnel.
2. Copy the tunnel token from Cloudflare's connector installation command.
3. Add a published application route for your hostname, such as
   `car.example.com`, pointing to **HTTP** at `127.0.0.1:8090`.
4. In Strike's **Online** tab, open **Cloudflare tunnel → Settings**, enter that
   hostname and token, choose when the tunnel should run, and select **Save**.
5. Turn on **Remote access**, then open `https://car.example.com/` from your phone.
   Sign in with the browser access code. Cloudflare Access can provide an
   additional account login in front of Strike.

| Run mode | Behavior |
| --- | --- |
| Always | Runs while Strike has internet, including when driving |
| When car is off | Starts after ignition off; stops when ignition comes on |
| On lock | Requires ignition off and a confirmed lock; stops on unlock or ignition on |

Automatic modes stop when ignition readings are unknown or stale. **On lock**
waits for a confirmed lock reading; it has no timed fallback. The tunnel stops
when internet is unavailable and resumes when connectivity returns. Repeated
short process failures stop automatic retries until you press **Retry** or turn
remote access off and on.

The Cloudflare tunnel also appears on **Daemons**. Both switches control the same
setting. Turning it off stops the connector and cancels
retries, including automatic starts at the next lock or ignition change. Use the
car or its local network address to turn it back on. Turn it off before editing
or removing its setup; a blank token field keeps the saved token.

The token stays in private app storage and is never returned to the browser.
Cloudflared runs as a separate process; it shares Strike's existing HTTP server
and does not own a camera or encoder. While remote access is enabled, a foreground
service keeps the app available for its automatic schedule. There is no tunnel
process or supervisor thread when disabled. The connector also exits if the app
process dies.

The car needs internet and the head unit must remain awake. A tunnel cannot wake
it remotely. Remote playback depends on the car's upload speed and the browser's
codec support.

Cloudflare's [video policy](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/delivering-videos-with-cloudflare/)
applies to public tunnel hostnames on Free, Pro and Business plans. Check those
terms before using recordings or Live through the tunnel. Private network routes
are a separate Cloudflare setup and are not configured by this tab.

The APK includes cloudflared 2026.8.3 for its target architecture. To rebuild it,
install Go and the Android NDK listed above, then run
`.\tools\build-cloudflared.ps1` from PowerShell. Add `-Abi x86_64` for the emulator
connector. The script pins and verifies the upstream source, selects Go 1.26.6,
and adds the parent-exit guard in `tools/cloudflared/parent_android.go`. Normal APK
builds use the bundled binary. Its [license notices](app/src/main/assets/cloudflared-notices.txt)
are included in the APK.

## Updates

Open **Dashboard → Updates** to check, download, or install a release. Strike checks
GitHub when the app opens or resumes, at most once every 24 hours. **Check now**
checks immediately. Downloads and installation require your action;
there is no background update service.

Before installation, Strike checks the APK's checksum, package, signing key,
version, Android requirement, and processor architecture. It finishes the current
clip before installing and restarts recording afterward if the recorder was
running. If the clip cannot finish, installation is cancelled. Settings and saved
footage are preserved.

In-app installation uses the same authorised shell access as the recorder. A
debug build cannot update to a release signed with a different key. Keep using
the same signing key for all published APKs.

To publish an update:

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build a signed release APK with the original keystore.
3. Create a GitHub release with a matching tag, such as `v0.2` for version `0.2`.
4. Attach the signed APK as **Strike.apk** and publish the release as the latest
   stable release. Drafts and prereleases are not offered by the checker.

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
