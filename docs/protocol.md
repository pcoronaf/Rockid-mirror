# Wire protocol (version 1)

Two channels between one sender (phone) and one receiver (glasses):

* **Control**: TCP, length-prefixed frames (`u32` big-endian length + bytes, up to 256 KB because
  preview snapshots travel here; a message that would exceed it is dropped, never the link), JSON messages
  (`protocol/schema/control-message.schema.json`, payload table in `protocol/schema/payloads.md`).
  Plaintext only during the handshake, then AES-256-GCM.
* **Video**: UDP datagrams ≤ 1400 bytes: 40-byte cleartext header + AEAD-sealed fragment payload.

Ports: control 47010 (advertised in DNS-SD), video 47011 (announced in `HELLO_ACK.videoPort`).
Discovery: DNS-SD `_rokidmirror._tcp`, TXT `id`, `name`, `w`, `h`, `v`.

## Handshake

```
S → R  HELLO      {senderId, senderName, ephemeralPublicKey (P-256), credentialId?, nonce}
R → S  HELLO_ACK  {receiverId, ..., ephemeralPublicKey, videoPort, pairingRequired, nonce}
       both: secret = ECDH; T = SHA256("rokid-mirror-transcript-v1" ‖ len‖HELLO bytes ‖ len‖HELLO_ACK bytes)
             keys = HKDF-SHA256(secret, salt = T): control s→r, control r→s, video s→r
```
Then one of:

**Credential path** (receiver knows `credentialId` for `senderId`):
```
S → R  PAIR_REQUEST {mode: CREDENTIAL, stage: CREDENTIAL, credentialId, proof = HMAC(secret, "…proof-v1" ‖ 'A' ‖ T)}
R → S  PAIR_CONFIRM {stage: CREDENTIAL, accepted, proof = HMAC(secret, "…" ‖ 'B' ‖ T)}      ← last plaintext frame
```
**Code path** (glasses show a 6-digit code `c`, 20 bits, single use, 90 s TTL). For each bit i = 0..19:
```
S → R  PAIR_REQUEST {mode: CODE, stage: COMMIT, round: i, commitment: HMAC(Na_i, 'A' ‖ T ‖ i ‖ bit_i(c))}
R → S  PAIR_CONFIRM {stage: COMMIT, round: i, commitment: HMAC(Nb_i, 'B' ‖ T ‖ i ‖ bit_i(c))}
S → R  PAIR_REQUEST {stage: REVEAL, round: i, nonce: Na_i}     receiver verifies or aborts (code burned)
R → S  PAIR_CONFIRM {stage: REVEAL, round: i, nonce: Nb_i}     sender verifies or aborts
```
After round 19 the receiver switches to encryption and sends `PAIR_CONFIRM {stage: ISSUE,
credentialId, credentialSecret}` (encrypted); the sender persists it. An attacker who must guess
each bit before it is revealed succeeds with probability 2^-20 and any failure ends the code's life.
Commitments include `T`, so they cannot be relayed into another key exchange.

From here every control frame is `u64 counter ‖ AES-GCM(key_dir, nonce = dir ‖ 0³ ‖ counter, plaintext)`;
counters must be strictly increasing.

## Session

```
R → S  CAPABILITIES   display, codecs, maxDecode, input, model/os            (required before video)
S → R  STREAM_START   codec, w, h, fps, bitrate, preset, sourceName, sessionShortId
S → R  STREAM_FORMAT  codec, w, h, fps, csd0/csd1 (base64 SPS/PPS), sourceW/H, rotation   (re-sent on change)
S → R  VIEWPORT_SET / PROFILE_SET                                             (throttled to 25 Hz)
S → R  POINTER        {x, y, visible, pressed}  mouse-mode cursor in normalized source coords,
                      throttled to 25 Hz; the receiver draws it in its overlay
S → R  VISION_SET    {enabled, contrast}  camera view on the glasses
S → R  PREVIEW_SET   {enabled, fps, maxWidth, quality}  ask for snapshots of the glasses' display
R → S  PREVIEW_FRAME {data (base64 JPEG), width, height, kind, capturedNs}  a few per second while asked
both   PING {sentNs} / PONG {sentNs, receiverNs}  every 1 s → RTT + clock offset (ClockSync)
R → S  STATS          every 1 s (see payloads.md)
R → S  KEYFRAME_REQUEST after loss / decoder drop / reconfigure (rate-limited 300 ms)
S → R  STREAM_STOP, both GOODBYE, both ERROR {code, message, recoverable}
```
Control silence > 5 s = link lost. The sender reconnects (new HELLO, new keys, credential path),
then repeats `STREAM_START`, `STREAM_FORMAT`, `VIEWPORT_SET` and forces an IDR.

## Video datagram

```
 0  magic u16 = 0x524D   2 version u8 = 1   3 flags u8 (1 KEYFRAME, 2 CONFIG, 4 END_OF_AU, 8 RETRANSMIT)
 4  sessionShortId u32   8 frameId u32     12 packetSequence u32
16  fragmentIndex u16   18 fragmentCount u16   20 payloadLength u16   22 reserved u16 = 0
24  captureTimestampNs i64 (T0)   32 encodeTimestampNs i64 (T1)   40 payload (AEAD ciphertext, 16-byte tag)
```
Nonce = `'V' ‖ 0³ ‖ sessionShortId ‖ packetSequence`; the header is the associated data. One access
unit = one frame (SPS+PPS+IDR for keyframes). Fragments are 1344 bytes of plaintext each.

## Reassembly policy (receiver, `protocol/video/Reassembler.kt`)

* Frames complete when all fragments arrived; delivered in frame order.
* A frame whose fragments are still missing 120 ms after its first packet is dropped; if a newer
  keyframe is already complete, older incomplete frames are dropped immediately.
* After any drop, delta frames are discarded until the next keyframe and a `KEYFRAME_REQUEST` is suggested.
* Sequence gaps are counted as loss; duplicates and other sessions are ignored. No retransmission.

## Compatibility policy

`protocolVersion` is checked on every message and every datagram. Fields are added with defaults
and never change meaning; unknown fields are ignored (`ignoreUnknownKeys`) and an unrecognised
`type` decodes to `MessageType.UNKNOWN` and is skipped, so a peer can add message types without
breaking older builds. Anything else bumps the version, and a mismatch yields
`ERROR PROTOCOL_VERSION_MISMATCH` before any video flows.
