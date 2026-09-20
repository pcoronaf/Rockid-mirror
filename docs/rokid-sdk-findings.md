# Rokid SDK / runtime findings (Task C audit)

**Status: desk audit from public sources only. No RV101/RV102 was connected while this repository
was written. Every row marked *UNVERIFIED* must be confirmed on the device before M0 is closed,
and this file must then be updated with the measured values.** Nothing in the production code path
calls a Rokid-specific API; see "Decision" below.

| Item (spec checklist) | Finding | Confidence / source |
|---|---|---|
| Exact glasses model | Rokid Glasses, models RV101 / RV102 (consumer "Rokid Glasses" 2025; RV101 also sold as enterprise) | Rokid security-center model mapping; VR-Expert comparison |
| OS / runtime | **YodaOS-Sprite, based on Android 12 (SDK/API 32)**, Qualcomm QSSI build; build id seen in community dumps `SKQ1.240613.001` | community docs (infosys-reduxel / buildwithfenna `rokid-docs`, `yodaos/docs/overview.md`); cursive-team `rokid-apps` README ("Android 12, SDK 32, 480x640, 240dpi") — *UNVERIFIED on our unit* |
| SoC / ABI / memory | Qualcomm Snapdragon AR1 (+ NXP RT600 low-power MCU), arm64-v8a, 2 GB RAM, 32 GB storage | Rokid launch material; community docs — *UNVERIFIED* |
| SDK version | Rokid "Glasses SDK" `com.rokid.security:glass3.open.sdk:2.2.0-E` and phone-side `com.rokid.security:phone.sdk:2.2.0-E` from `https://maven.rokid.com/repository/maven-public/`; "supports Android 8.0 or later", Android Studio 2022+, JDK 17 | x-docs.rokid.com Glass3 SDK Quick Start (fetched 2026-09-20) |
| Application packaging | Standard **Android APK** | vendor quick start + all community apps |
| Implementation languages | Kotlin/Java (Android); vendor also offers an AIUI/JSAR JavaScript agent runtime, not relevant here | vendor docs |
| Native code support | Standard Android NDK expected (arm64-v8a) — not needed by this project | inferred, *UNVERIFIED* |
| Network APIs | `java.net` sockets, `NsdManager` (DNS-SD), `ConnectivityManager` — standard Android 12. Community projects use UDP discovery + TCP/HTTP video successfully (`0suu/rokid-glasses-virtual-display`: UDP 8444 discovery, HTTP 8445 H.264 to the glasses) | community projects — *UNVERIFIED for multicast/NSD specifically* |
| Display / window APIs | Standard Activity/Window/SurfaceView. Display reported as **480×640 @ 240 dpi** (single green monochrome Micro-LED waveguide, right eye; ~30° FOV per product page). Note: some marketing pages quote 480×400 per eye — the app reads `WindowMetrics` at runtime and never hardcodes the size | community docs; product page — *UNVERIFIED* |
| GPU / rendering APIs | Standard (OpenGL ES / Vulkan via Android); this project needs only SurfaceFlinger composition | inferred, *UNVERIFIED* |
| Video decoder APIs | Android `MediaCodec`. `0suu/rokid-glasses-virtual-display` streams H.264 (with JPEG fallback) to a 480×640 glasses app, evidence that H.264 decode works for third-party apps | community project — *hardware vs software decode UNVERIFIED*: `PlatformCapabilities.probeDecoder()` logs `isHardwareAccelerated`, low-latency feature and max fps at 720p on first launch |
| Decoded frames straight to a display Surface | Standard `MediaCodec.configure(format, surface, …)` | Android API — *UNVERIFIED on device* |
| Touch-bar / input events | Temple touch bar (tap, forward/back swipe, long press), physical button; community apps use D-pad style `KeyEvent`s ("DpadNavigation" in `cursive-team/rokid-apps`), exact key codes not published | *UNVERIFIED*: `AndroidPlatformAdapter.onKeyEvent` logs every key code (`RokidRecv/Platform key_event`) — record them here |
| IMU / head pose | InvenSense ICM-4x6xx (accel + gyro) present; Extentos ecosystem notes list IMU exposure through the Glasses SDK (W3C Generic-Sensor shape). Whether Android `SensorManager` rotation vectors are available to third-party apps is unknown | *UNVERIFIED*: `getSensorCapabilities()` reports at runtime |
| App lifecycle restrictions | Unknown (launcher behaviour, background limits, whether an Activity can hold the screen on). App uses `FLAG_KEEP_SCREEN_ON` and runs only while visible | *UNVERIFIED* |
| Memory / process limits | Unknown beyond 2 GB total RAM | measure with `dumpsys meminfo` during Task D |

## Install / debug procedure (documented, to be confirmed)

1. In the **Hi Rokid** phone app (glasses paired), enable developer/ADB debugging for the glasses.
2. Connect the **5-pin data/debug cable** ("Glass3 data debug cable"; the 3-pin cable is charge-only).
3. `adb devices -l` lists the glasses; `adb install -r <apk>`; `adb shell am start -n <component>`.
4. Alternatives seen in the community: Hi Rokid Toolbox local-APK install; `Anezium/Rokid-APKs`
   (CXR-M / Bluetooth SPP / Wi-Fi LAN); `mlustosa/rokid-glass-transfer` (UDP discovery + HTTP);
   WebUSB installer (`eung.pe.kr/web-install`).

Sources: https://x-docs.rokid.com/docs/en/terminal-sdk/ (Glasses SDK, Quick Start),
https://open.rokid.com/ (ar.rokid.com/sprite redirects here), https://github.com/buildwithfenna/rokid-docs,
https://github.com/cursive-team/rokid-apps, https://github.com/0suu/rokid-glasses-virtual-display,
https://github.com/Anezium/awesome-rokid, https://extentos.com/docs/ecosystem/platforms/rokid,
https://global.rokid.com/blogs/news (launch specs). Re-check vendor docs when work resumes.

## Decision

* The receiver is a plain Android 12 APK using `MediaCodec`, `SurfaceView`, `NsdManager`, sockets,
  `SensorManager`, `KeyEvent`. **No Rokid SDK symbol is used**, so no API had to be invented.
  The Glasses SDK covers camera, voice/AI, messaging, device state, Bluetooth and P2P — none is
  required for mirroring. Its Maven repository is pre-configured in `rokid-receiver/settings.gradle.kts`
  for a future `RokidSdkPlatformAdapter` (touch bar / IMU) if the audit shows the framework APIs
  are insufficient.
* `rokid-receiver/app` sets `minSdk 31`, `targetSdk 32` to match the device.

## Tracked blockers (close during M0 on hardware)

- [ ] Confirm API level, display size and refresh rate (first-launch log line `RokidRecv/Activity platform`).
- [ ] Confirm H.264 decoder name, `isHardwareAccelerated`, low-latency feature, sustained 480p30 then 720p30.
- [ ] Record temple touch bar key codes and fix the mapping in `AndroidPlatformAdapter.onKeyEvent`.
- [ ] Confirm `SensorManager` rotation vector availability (head-controlled viewport is off by default).
- [ ] Confirm `NsdManager` registration works on YodaOS-Sprite (fallback: manual IP on the phone).
- [ ] Confirm the SurfaceView can be laid out larger than the display (fallback: TextureView + matrix).
- [ ] Confirm keep-screen-on / lifecycle behaviour for a foreground Activity.
