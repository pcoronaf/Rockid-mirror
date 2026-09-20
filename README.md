# Rokid Glasses Phone Mirroring

Mirror an Android phone (primary target: Samsung Galaxy S25) — the whole screen or a single app —
onto **Rokid Glasses (RV101/RV102, YodaOS-Sprite)** over a low-latency, encrypted local
network link. No root, no cloud, Android standard APIs only on the phone.

Two apps plus a shared protocol library:

| Part | What it is | Status |
|---|---|---|
| `android-sender/` | Kotlin/Compose phone app: MediaProjection → VirtualDisplay → H.264 MediaCodec Surface encoder → encrypted LAN transport; DNS-SD discovery; pairing; zoom/pan/profiles; adaptive bitrate; diagnostics | builds, unit-tested, **not yet run on a device** |
| `rokid-receiver/` | Android 12 (API 32) app for the glasses: control server + pairing, AEAD UDP video, hardware MediaCodec decode straight to a SurfaceView, zero-copy fit/fill/zoom/pan viewport, overlay, optional head-motion viewport | builds, unit-tested, **not yet run on RV101/RV102** |
| `protocol/` | Shared wire protocol: versioned JSON control messages, 40-byte video packet header, fragmentation/reassembly, ECDH + HKDF + AES-GCM session crypto, bitwise commit/reveal pairing | 39 JVM tests |
| `tools/` | `mock-receiver` (desktop stand-in for the glasses), `stream-generator` (replays an H.264 file over the protocol), `packet-inspector`, `latency-marker` procedure, `e2e-loopback.sh` | builds; loopback e2e passes |
| `docs/` | architecture, protocol, Rokid SDK findings, latency testing, security, dependencies, M0 feasibility report | see below |

**Where this stands against the spec's milestones:** the code covers M1–M3 functionality and
the M2 security model, but the specification's **M0 gate (prove the glasses can decode and
display a stream) has not been executed** because no RV101/RV102 was available in this
environment. Everything the receiver does uses standard Android 12 APIs, which public Rokid
documentation says the glasses expose to third-party APKs; that assumption is recorded, with
its sources and the open items, in [`docs/rokid-sdk-findings.md`](docs/rokid-sdk-findings.md)
and [`docs/m0-feasibility-report.md`](docs/m0-feasibility-report.md). Run Task D
(`tools/stream-generator` → glasses) first when hardware is available.

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
2. Phone: open **Rokid Mirror**. The receiver appears under *Receivers*; tap **Connect**.
3. First time only: the glasses display a 6-digit code; type it on the phone. A credential is
   stored on both sides (Android Keystore) so later connections are automatic.
   *Forget receiver* on the phone / long-press Back on the glasses (while idle) revokes it.
4. Tap **Mirror phone** (whole display) or **Select app** (Android 14+ single-app picker) and
   accept the system consent dialog. A persistent notification shows while mirroring.
5. Use Fit / Fill / 100% / Zoom, the sliders or the control surface (drag = pan, pinch = zoom,
   double tap = Fit) to make small text readable on the 480×640 display. Save the view as a
   profile. **Test pattern** streams a synthetic source without capture consent (development).
6. *Diagnostics* shows and exports the sanitized latency/quality report.

## Security summary

Local only, no accounts. Pairing uses a per-bit commit/reveal protocol bound to an ephemeral
ECDH exchange, so an on-path attacker cannot brute-force the code; sessions are AES-256-GCM
(control and video) with per-session keys; unknown senders never stream. Video is never written
to storage during normal operation (only the desktop mock can dump it, for debugging). Android's
FLAG_SECURE / DRM / consent rules are respected, not bypassed. Details: `docs/security.md`.

## License

Placeholder — see `LICENSE`. Third-party licenses: `docs/dependencies.md`.
