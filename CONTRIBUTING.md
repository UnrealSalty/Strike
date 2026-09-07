# Contributing to Strike

Strike focuses on recording, parked surveillance, and the dashboard used to
control them. The current hardware baseline is the Atto 2 described in the
[README](README.md#compatibility).

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

Follow the [build setup](README.md#build), then run from the repository root:

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

## Reference code and reports

Overdrive is a reference for BYD hardware integration. When using it to support a
change, identify the relevant implementation and what it confirms. Keep unrelated
architecture, dependencies, and UI out of Strike.

Bug reports should include the vehicle and firmware, Strike build, steps to
reproduce, and expected versus actual behavior. Add relevant logs or screenshots
when available; remove credentials, precise locations, and private footage before
sharing them. Keep signing keys, tokens, `local.properties`, and generated build
files out of contributions.
