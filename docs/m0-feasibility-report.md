# M0 feasibility report — Rokid Glasses as a low-latency video endpoint

**Status: NOT YET EXECUTED ON HARDWARE.** This document is the report template plus everything
known from the desk audit. The M0 gate stays open until the numbered measurements below are
filled in from a real RV101/RV102. Per the specification, production networking work should not
be considered final until this report says the decode/render path is viable.

## 1. Hardware and firmware
| | Value | Source |
|---|---|---|
| Model | RV101 / RV102 — *record from `Build.MODEL`, `Build.DEVICE`* | — |
| OS / API | YodaOS-Sprite (Android 12 / API 32 expected) — *record `Build.VERSION.RELEASE`, `SDK_INT`, `Build.DISPLAY`* | community docs |
| Display | 480×640 @ 240 dpi expected — *record `WindowMetrics` + `Display.refreshRate` from the first-launch log* | community docs |

## 2. Verified SDK / platform APIs
Expected: standard Android (`MediaCodec`, `SurfaceView`, `NsdManager`, sockets). *Record what the
receiver logged (`RokidRecv/Activity platform …`) and which blockers in `rokid-sdk-findings.md`
were closed.*

## 3. Codec path
*Record `decoder=`, `hw=`, `lowLatency=`, `maxDecode=` from the log; profiles supported.*

## 4. Measured frame rate (Task D, `tools/stream-generator`, 10 min)
| Source | fps (min/median) | dropped | lag frames | notes |
|---|---|---|---|---|
| 480p30 | | | | |
| 720p30 (only if 480p30 passed) | | | | |

## 5. Latency
| Stage | median ms | p95 ms |
|---|---|---|
| reassembly (T4−T3) | | |
| decode (T6−T5) | | |
| render (T7−T6) | | |
| RTT (phone↔glasses) | | |
| optical glass-to-glass (tools/latency-marker) | | |

## 6. CPU / memory (dumpsys every 30 s for 10 min)
PSS start/end, CPU % of the receiver process, any GC pressure.

## 7. Thermal
`dumpsys thermalservice` status over the run; skin temperature if reported; throttling observed?

## 8. Unresolved blockers
Copy the open items from `rokid-sdk-findings.md` that still apply.

## 9. Decision
☐ proceed  ☐ proceed with constraints (state them: e.g. 480p30 only, software decode)  ☐ architecture change required (state the closest viable alternative)

### Desk-audit assessment (pre-hardware)
Evidence that the path is viable: the glasses run Android 12 and accept ordinary APKs; at least
one community project already streams H.264 at 480×640 to a glasses-side app over Wi-Fi; the
Snapdragon AR1 has a hardware video decoder. Main risks: whether the hardware decoder is exposed
to third-party apps and its low-latency behaviour, NSD/multicast availability on the glasses'
Wi-Fi stack, and the key-event mapping of the temple touch bar. None of these changes the
architecture; the worst realistic case is software decode limited to 480p30, which the
capability negotiation already handles (`PlatformCapabilities`).
