# M0 feasibility report — Rokid Glasses as a low-latency video endpoint

**Status: CLOSED. Decision: proceed with constraints.**

The gate asked one question: can the glasses receive, decode and continuously display an
external low-latency video stream? They can. Below are measurements from a Samsung Galaxy S25
(Android 16) streaming to Rokid Glasses over Wi-Fi, taken from the sender's diagnostics export
and the receiver's own boot line. Values marked *not measured* are exactly that; nothing here is
estimated and presented as measured.

## 1. Hardware and firmware

| | Value | Source |
|---|---|---|
| Glasses | Rokid RG-glasses (device `glasses`), reported as `RG-glasses 75f9` | receiver `PlatformDescription` |
| OS / API | Android 12, API 32, build `SKQ1.240613.001 release-keys` | receiver, on device |
| ABI | arm64-v8a | receiver, on device |
| Display | 480 × 640, green monochrome waveguide | receiver `WindowMetrics` |
| Phone | Samsung SM-S938B, Android 16 (API 36) | sender |
| Phone encoder | `c2.qti.avc.encoder`, low-latency feature **not** exposed | sender `MediaCodecList` probe |

## 2. Verified platform APIs

Everything the receiver uses is standard Android 12, and no Rokid SDK symbol appears in the
production path. Confirmed working on device: `MediaCodec` H.264 decode to a `SurfaceView`,
`NsdManager` DNS-SD advertising and discovery, TCP and UDP sockets, `KeyEvent` input from the
temple bar, Android Keystore for credentials, and a foreground service that keeps the session
alive without a window.

Two platform limits were established rather than assumed:

- **A second display cannot host other apps.** `ActivityOptions.setLaunchDisplayId` onto a
  virtual display created by an ordinary app is refused: `Permission Denial: starting Intent …
  with launchDisplayId=17`. No user-grantable permission lifts it. The sender's own activity
  launches there, so extended-screen mode carries our own workspace.
- **The frame-rendered callback is not on our clock.** This decoder reported render timestamps
  around nine days away from `System.nanoTime()`. Render latency is therefore discarded rather
  than reported, and shows as unmeasured.

## 3. Codec path

H.264 to a decoder-owned `Surface`, no CPU copy, no playout buffer. The decoder name, whether it
is hardware accelerated and its advertised maximum are now reported to the phone in
`CAPABILITIES` and appear in the diagnostics export; record them here from the next run.

## 4. Measured frame rate

| Source | Result |
|---|---|
| 720 × 1280 at 60 fps | sustained **60.1 fps at 4250 kbps**, no growing decode lag |
| 288 × 640 at 30 fps | 23.2 fps under 1.42 % packet loss, after the adaptive controller had reduced quality |

720p60 decode is comfortably within the hardware's reach, which exceeds the 480p30 bar the gate
asked for. The 23.2 fps figure is a network-loss artefact, not a decoder limit.

## 5. Latency

Measured over a 558 s session on a healthy link:

| Stage | Value |
|---|---|
| Encode (T1−T0) | 5.1 ms average, 6.1 ms maximum |
| Network (RTT/2) | 5.4 ms, round trip 10.8 ms |
| Reassembly (T4−T3) | 3.1 ms |
| Decode (T6−T5) | 5.6 ms |
| Render (T7−T6) | not measured, see section 2 |
| **Software pipeline total** | **≈ 19 ms** |

That is well inside the specification's 70 ms preference and 100 ms maximum, before display
latency. On a poorer network earlier in testing the round trip was 159 ms, so the link, not the
pipeline, dominates the end-to-end figure.

Optical glass-to-glass latency is **not measured**. The tooling exists (`tools/latency-marker`)
and remains the honest way to state a user-facing number.

## 6. CPU and memory

Not measured. No instability was observed across sessions exceeding one hour.

## 7. Thermal

More than one hour of continuous use with **no perceptible warming** of the glasses, reported by
the operator. No throttling was observed in the frame-rate record.

## 8. Unresolved items

- Optical end-to-end latency has not been measured.
- Receiver CPU and memory have not been sampled with `dumpsys`.
- Packet loss of 1.42 % was seen on Wi-Fi. It is tolerated by keyframe recovery, and the
  adaptive controller no longer reduces quality against loss it cannot influence.
- The temple bar's exact key codes are still unrecorded; the app accepts a set of candidates and
  displays every raw code it receives so the set can be narrowed.
- The receiver's decoder component name has not yet been copied into section 3.

## 9. Decision

**Proceed with constraints.** The decode and render path is viable and faster than required.
The constraints to carry forward are the ones above: second displays cannot host third-party
apps, render timing cannot be measured on this decoder, and the radio, not the pipeline, sets
the latency the user feels.
