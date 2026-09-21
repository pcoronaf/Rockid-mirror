# Architecture

```
Galaxy S25 (android-sender)                              Rokid Glasses (rokid-receiver)
┌──────────────────────────────────────────┐             ┌──────────────────────────────────────────┐
│ Compose UI ── MirrorSession (coordinator)│             │ ReceiverActivity (composition root)      │
│      │            │          │           │             │      │                                    │
│ MediaProjection   │      LanStreamTransport ═══ TCP ═══▶ ReceiverTransport ── ReceiverSession      │
│      │            │          ║  control   │   AES-GCM  │      ║ (handshake, control, stats)        │
│ VirtualDisplay ─▶ MediaCodec ║ ── UDP ════════════════▶ Reassembler ─▶ MediaCodecVideoDecoder      │
│   (one per       H.264 enc.  ║  video     │   AEAD     │      (protocol)        │ (Surface output) │
│   consent)       Surface in  ║            │            │                        ▼                  │
│                   │          ║            │            │              ViewportRenderer (SurfaceView │
│ AdaptiveController◀─ stats ──╢            │            │              layout = zoom/pan, zero copy) │
│ LatencyTracker  ProfileRepo  ║            │            │              OverlayView                   │
└──────────────────────────────╨───────────┘             │ RokidPlatformAdapter (display, keep-awake,│
                                                         │   keys, sensors)  HeadViewportController   │
                                                         └──────────────────────────────────────────┘
```

## Principles applied

* **Captured pixels never touch the CPU.** `VirtualDisplay → encoder input Surface` on the phone;
  `decoder → SurfaceView surface` on the glasses. The only copies are of compressed access units.
* **Modules are independent.** Sender: `capture`, `encoder`, `transport`, `discovery`, `control`,
  `telemetry` know nothing of each other; `app/session/MirrorSession` is the only glue. Receiver:
  `ReceiverActivity` wires modules through tiny bridge interfaces defined in `control`.
  Nothing outside `transport` touches sockets or ciphers.
* **Rokid access is fenced.** `rokid-receiver/platform/RokidPlatformAdapter` is the boundary.
  Today's implementation is standard Android only; the Rokid Glasses SDK is not a dependency.
* **Latency over quality.** No playout buffer anywhere. Late/incomplete delta frames are dropped and
  a keyframe requested; the sender's send queue drops deltas under backpressure until the next
  keyframe; the adaptive controller lowers bitrate, then fps, then resolution.

## Sender data flow

1. UI → `MediaProjectionManager.createScreenCaptureIntent(config)` (whole display or user choice on
   Android 14+). The activity forwards the result to `MirrorService` (foreground, type
   `mediaProjection`) which calls `MirrorSession.startProjectionStreaming`.
2. `StreamNegotiator` picks encoded size/fps/bitrate from preset × receiver `CAPABILITIES` ×
   `EncoderCapabilities.probe()`. `MediaCodecVideoEncoder.start` returns the input Surface.
3. `MediaProjectionCaptureController.start` creates **one** VirtualDisplay on that Surface. Content
   resize (`onCapturedContentResize`) → encoder restart with a new Surface → `VirtualDisplay.resize`
   + `setSurface` (never a second projection). `onStop` → immediate teardown → UI back to Ready.
4. Encoder output callback → `EncodedAccessUnit` (SPS/PPS prepended to every IDR) →
   `LanStreamTransport.sendVideo` (bounded queue, dedicated send thread, `Fragmenter` + `VideoCipher`).
5. Once per second: `LatencyTracker` snapshot + receiver `STATS` + RTT → `AdaptiveController` →
   bitrate (`setParameters`), fps/resolution (encoder restart + VD resize).
6. Link loss → `Recovering`: reconnect with backoff for 20 s using the stored credential, then
   re-send `STREAM_START`/`STREAM_FORMAT`/`VIEWPORT_SET` and force a keyframe.

## Receiver data flow

1. `ReceiverTransport` listens on TCP 47010 (control) and UDP 47011 (video) and advertises
   `_rokidmirror._tcp` via `NsdManager` with TXT `id,name,w,h,v`.
2. Handshake (`protocol/crypto/Handshake.kt`) → `CAPABILITIES` (from `PlatformCapabilities`, i.e.
   real `MediaCodecList` + display facts) → `STREAM_START` binds the session short id →
   `STREAM_FORMAT` configures the decoder (csd from the message; keyframes also carry SPS/PPS).
3. UDP thread: header parse → AEAD open (header is AAD) → `Reassembler` → complete AU →
   `MediaCodecVideoDecoder.submit` (async MediaCodec, `releaseOutputBuffer(render=true)` immediately).
4. `ViewportRenderer` positions the SurfaceView from `ViewportMath.placement`: the decoder always
   renders the whole frame, the compositor scales/crops. Phone `VIEWPORT_SET`/`PROFILE_SET`, temple
   keys and (optionally) head pose update the same state.
5. `PipelineStats` records T3..T7 per frame and produces `STATS` every second; the reassembler's
   loss signals and decoder drops trigger rate-limited `KEYFRAME_REQUEST`s.

## Receiver process model

The link, the DNS-SD advertisement and the session live in `ReceiverService`, a foreground
service, so the glasses' launcher, a sleep or another app taking the foreground no longer ends
the session and forces re-pairing. `ReceiverActivity` contributes a `DisplayTarget` (renderer,
overlay, decoder, input adapter) while it has a window, and the service's bridges absorb calls
while it does not. A stream that arrives with no window asks the platform to bring the Activity
forward; if that is refused, the link stays up and video resumes when the user opens the app.

## Threading

* Sender: MediaCodec callbacks on a dedicated HandlerThread; UDP send on its own thread; control I/O
  on `Dispatchers.IO`; session logic on `Dispatchers.Default` guarded by a mutex; UI observes
  `StateFlow`s.
* Receiver: accept thread, one control thread per session, UDP receive thread (max priority - 1),
  decoder callbacks on a HandlerThread, view updates posted to main.

## Decisions and alternatives

| Decision | Why | Alternative kept open |
|---|---|---|
| Custom UDP transport instead of WebRTC | WebRTC on a 2 GB, API 32 glasses runtime is unproven and heavy; the spec allows the fallback; the design keeps sequence/frame/fragment ids, keyframe recovery, stats and encryption. | `StreamTransport` interface; WebRTC implementation can replace `LanStreamTransport` if M0 shows headroom. |
| SurfaceView layout for zoom/pan | Zero extra GPU pass on the glasses; immediate transitions. | `TextureView` + matrix if the device rejects oversized surfaces. |
| H.264 only | Decoder compatibility; HEVC only after receiver capability is proven (spec). | `VideoCodec.H265` is already in the enum/negotiation. |
| Bitwise commit/reveal pairing | PAKE-grade resistance to on-path attackers with only JCA primitives available on API 32 (no X25519, no group ops). | SPAKE2 if a vetted library is adopted later. |
| Keystore-wrapped credentials, no security-crypto lib | One fewer dependency; same guarantee. | — |
| Mouse mode clicks via a gesture-only accessibility service | The only no-root way to tap another app; configured with no event subscriptions and no content access. | Cursor-only mode when the service is off. |
| Extended screen via DisplayManager, not MediaProjection | A virtual display with `OWN_CONTENT_ONLY` shows only what we launch on it, so it is a second screen and needs no capture consent. | Mirroring stays the MediaProjection path. |
| Generated sources drawn through EGL | A MediaCodec input surface cannot be painted with a locked Canvas; the supported route is an EGL window surface. Frames are still authored with Canvas, then uploaded as a texture. | — |
| Cursor drawn by the receiver overlay | No bandwidth, crisp at any zoom, and no SYSTEM_ALERT_WINDOW permission on the phone. | Overlay window on the phone if the cursor must appear in recordings. |
