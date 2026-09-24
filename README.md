# Rokid Glasses Phone Mirroring

Mirror an Android phone (primary target: Samsung Galaxy S25) — the whole screen or a single app —
onto **Rokid Glasses (RV101/RV102, YodaOS-Sprite)** over a low-latency, encrypted local
network link. No root, no cloud, Android standard APIs only on the phone.

Two apps plus a shared protocol library:

| Part | What it is | Status |
|---|---|---|
| `android-sender/` | Kotlin/Compose phone app: MediaProjection → VirtualDisplay → H.264 MediaCodec Surface encoder → encrypted LAN transport; DNS-SD discovery; pairing; zoom/pan/profiles; mouse mode; extended screen; adaptive bitrate; diagnostics | running on a Galaxy S25 |
| `rokid-receiver/` | Android 12 (API 32) app for the glasses: foreground service holding the link, control server + pairing, AEAD UDP video, MediaCodec decode straight to a SurfaceView, zero-copy fit/fill/zoom/pan viewport, overlay, optional head-motion viewport | running on Rokid Glasses |
| `protocol/` | Shared wire protocol: versioned JSON control messages, 40-byte video packet header, fragmentation/reassembly, ECDH + HKDF + AES-GCM session crypto, bitwise commit/reveal pairing | 39 JVM tests |
| `tools/` | `mock-receiver` (desktop stand-in for the glasses), `stream-generator` (replays an H.264 file over the protocol), `packet-inspector`, `latency-marker` procedure, `e2e-loopback.sh` | builds; loopback e2e passes |
| `docs/` | architecture, protocol, Rokid SDK findings, latency testing, security, dependencies, M0 feasibility report | see below |

**Where this stands against the spec's milestones:** the M0 gate is **closed: proceed with
constraints**. On real hardware the glasses sustain 720p60 decode at 4250 kbps, and the software
pipeline measures about 19 ms end to end (encode 5.1, network 5.4, reassembly 3.1, decode 5.6),
with more than an hour of continuous use and no perceptible warming. Measurements, the platform
limits that were established rather than assumed, and what remains unmeasured are in
[`docs/m0-feasibility-report.md`](docs/m0-feasibility-report.md). The code covers M1–M3
functionality and the M2 security model.

## Repository layout

```
rokid-phone-mirror/
├── docs/                    architecture, protocol, rokid-sdk-findings, latency-testing, security, dependencies, m0 report
├── protocol/                kotlin/ (shared library, JVM tests), schema/ (JSON schema + payload table), examples/
├── android-sender/          app, capture, encoder, transport, discovery, control, telemetry, test/
├── rokid-receiver/          app, platform, decoder, transport, renderer, control, telemetry, test/
├── tools/                   mock-receiver, stream-generator, packet-inspector, latency-marker, e2e-loopback.sh
├── gradle/libs.versions.toml   single version catalog shared by all four Gradle builds
└── .github/workflows/ci.yml
```

The sender and receiver are separate Gradle builds (different runtime constraints); both include
`protocol/kotlin` as a composite build.

## Building

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35 (`ANDROID_HOME` set).

```sh
(cd protocol/kotlin && ./gradlew test)                       # shared library tests
(cd android-sender  && ./gradlew testDebugUnitTest assembleDebug)   # phone APK
(cd rokid-receiver  && ./gradlew testDebugUnitTest assembleDebug)   # glasses APK
(cd tools           && ./gradlew build) && tools/e2e-loopback.sh    # desktop tools + protocol e2e
```

APKs land in `*/app/build/outputs/apk/debug/`.

## Installing on the glasses (documented procedure, to be confirmed on device)

From community and vendor documentation (details and sources in `docs/rokid-sdk-findings.md`):

1. Enable debugging for the glasses from the **Hi Rokid** companion app on a paired phone.
2. Connect the glasses with the **5-pin data/debug cable** (the 3-pin cable only charges).
3. `adb devices -l` should list the glasses. Then:
   ```sh
   adb install -r rokid-receiver/app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.rokidmirror.receiver.debug/com.rokidmirror.receiver.app.ReceiverActivity
   ```
4. Put the glasses on the same Wi-Fi as the phone, or connect them to the phone's hotspot.

## Using it

1. Glasses: launch **Rokid Mirror Receiver**. It shows *waiting for phone* and advertises itself.
   A foreground service keeps the link alive when the window is not in front, so leaving the app
   does not drop the pairing; double tapping the temple stops it properly.
2. Phone: open **Rokid Mirror**. The receiver appears under *Receivers*; tap **Connect**.
3. First time only: the glasses display a 6-digit code; type it on the phone. A credential is
   stored on both sides (Android Keystore) so later connections are automatic.
   *Forget receiver* on the phone / long-press Back on the glasses (while idle) revokes it.
4. Tap **Mirror phone** (whole display) or **Select app** (Android 14+ single-app picker) and
   accept the system consent dialog. A persistent notification shows while mirroring.
5. Use Fit / Fill / 100% / Zoom, the sliders or the control surface (drag = pan, pinch = zoom,
   double tap = Fit) to make small text readable on the 480×640 display. Save the view as a
   profile. **Test pattern** and **Matrix rain** stream a generated picture without capture
   consent, which is the quickest way to check the link.
6. Switch the pad to **Mouse** to drive a cursor over the mirrored screen: drag moves it, tap
   clicks, long press holds, two fingers scroll, and Back / Home / Recents are one button each.
   Clicks need the *Rokid Mirror pointer* accessibility service, which the app offers to open in
   Settings; without it the cursor still moves so you can point at things. Clicks require
   whole-screen mirroring. See `docs/security.md` for exactly what that service can and cannot do.
7. **Extended screen** makes the glasses a second display rather than a copy of the phone. It
   renders at the glasses' own resolution (1:1, so text stays sharp) and needs no capture
   consent. Android refuses to launch *any* activity onto a display an ordinary app created,
   including the app's own (tested and confirmed on Android 16), so the second screen carries a
   workspace shown as a window: type an address on the phone and browse it on the glasses,
   driven by the Mouse pad. Because that content belongs to the sender, its input needs no
   accessibility service. If the platform declines the window too, the app offers "Display over
   other apps" as a second route.
8. **Glasses view** on the phone shows what the wearer is seeing. The glasses send back a few
   small snapshots a second, which carry the camera view and the overlay exactly. A decoded video
   frame cannot be read back from the decoder's surface, so while mirroring the phone instead
   draws the region of its own screen that is on the glasses, computed from the viewport it
   already owns: exact, and free. Snapshots stop when the view is closed.
9. **Glasses camera view** shows the glasses' own camera on the panel with the contrast lifted
   and four corners around each person facing you. Toggle it from the phone, or swipe forward on
   the temple when nothing is mirroring. It runs entirely on the glasses: nothing is recorded,
   nothing is transmitted, nobody is identified, and the device's camera indicator is left
   exactly as the platform drives it. See `docs/security.md`.
10. On the glasses, **double tap the temple** to exit, as in other Rokid apps. A single tap shows
   or hides the heads-up text, which hides itself a few seconds after the picture starts. Swipes
   step the zoom and a long press recenters. Warnings and the pairing code are never hidden, and
   if the picture stalls the glasses name the stage that stopped instead of showing black. A single tap toggles
   Fit and readable zoom, swipes step the zoom, and a long press recenters.
6. *Diagnostics* shows and exports the sanitized latency/quality report.

## Security summary

Local only, no accounts. Pairing uses a per-bit commit/reveal protocol bound to an ephemeral
ECDH exchange, so an on-path attacker cannot brute-force the code; sessions are AES-256-GCM
(control and video) with per-session keys; unknown senders never stream. Video is never written
to storage during normal operation (only the desktop mock can dump it, for debugging). Android's
FLAG_SECURE / DRM / consent rules are respected, not bypassed. Details: `docs/security.md`.

## License

Placeholder — see `LICENSE`. Third-party licenses: `docs/dependencies.md`.
