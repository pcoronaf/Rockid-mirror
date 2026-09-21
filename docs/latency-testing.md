# Latency instrumentation and testing

All timestamps are monotonic (`System.nanoTime()` on both devices; `SurfaceFlinger` frame
timestamps are `CLOCK_MONOTONIC` and are used as T0). Sender and receiver clocks are related by
`ClockSync` (min-RTT offset from PING/PONG), reported as `clockOffsetNs` in `STATS`.

| Point | Where measured | Field |
|---|---|---|
| T0 capture/submit | encoder output `presentationTimeUs` (surface timestamp) | `captureTimestampNs` in the packet header |
| T1 encoded AU available | `MediaCodec` output callback | `encodeTimestampNs` |
| T2 dispatch | `MirrorSession` when the AU is handed to the transport queue | `LatencyTracker.dispatchMsAvg` |
| T3 first packet arrival | receiver UDP thread | `Reassembler` (`firstPacketArrivalNs`) |
| T4 complete AU | receiver UDP thread | `reassemblyMs = T4 - T3` |
| T5 decoder submit | `MediaCodecVideoDecoder.submit` | — |
| T6 decoded frame | decoder output callback | `decodeMs = T6 - T5` |
| T7 presented | `MediaCodec.OnFrameRenderedListener` | `renderMs = T7 - T6`, or -1 when unmeasurable |

Some decoders report that callback's timestamp on a clock of their own; the Rokid unit produced
values around nine days from `System.nanoTime()`. Deltas outside 0 to 500 ms are discarded and
render latency reads as unmeasured rather than as a fabricated number.

Derived on the phone (Diagnostics screen / export): encode = T1 − T0; network ≈ RTT/2 (a direct
T2→T3 needs the clock offset and is reported as `networkMs` when available); reassembly, decode,
render from `STATS`; `estimatedLatencyMs` = encode + RTT/2 + reassembly + decode + render.
Software estimates exclude the phone display pipeline and the glasses panel; use the optical test
for glass-to-glass numbers.

## Optical test
See `tools/latency-marker/README.md`: the sender's *Marker* screen shows a frame counter, a ms
clock and a black/white flip square; film phone + glasses at 240 fps; report min/median/p95 over
≥ 30 samples.

## Measured on Rokid Glasses

See `docs/m0-feasibility-report.md`. On a healthy link the software pipeline totals about 19 ms:
encode 5.1, network 5.4, reassembly 3.1, decode 5.6. Round trip time between phone and glasses
has ranged from 10.8 ms to 159 ms depending on the network, and dominates everything else.

## Targets (spec)
startup < 3 s after consent; interactive < 70 ms preferred, < 100 ms max for MVP; 30 fps baseline;
reconnect < 5 s. If the glasses cannot reach them, document the measured distribution — never add
buffering to hide it.

## Soak tests
* Sender encoder: `android-sender/test/soak-encoder.sh 10` (Task B).
* Receiver with synthetic source: `tools/stream-generator … --loop` for 10 min (Task D);
  30 min end-to-end for M4 with `dumpsys meminfo` every 30 s; pass = flat memory, flat latency,
  no growing `decodeLagFrames`.
