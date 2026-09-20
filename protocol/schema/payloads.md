# Payload schemas (protocol v1)

Source of truth: `protocol/kotlin/src/main/kotlin/com/rokidmirror/protocol/control/Payloads.kt`.
All fields added after v1 must be optional with defaults. Binary fields are base64 strings.

| Type | Direction | Fields |
|---|---|---|
| HELLO | S→R | senderId, senderName, appVersion, ephemeralPublicKey (X.509 SPKI, EC P-256), credentialId?, nonce |
| HELLO_ACK | R→S | receiverId, receiverName, appVersion, ephemeralPublicKey, videoPort, pairingRequired, nonce |
| PAIR_REQUEST | S→R | mode (CODE/CREDENTIAL), stage (COMMIT/REVEAL/CREDENTIAL), round, commitment?, nonce?, proof?, credentialId? |
| PAIR_CONFIRM | R→S | stage (COMMIT/REVEAL/CREDENTIAL/ISSUE), accepted, round, commitment?, nonce?, proof?, credentialId?, credentialSecret? (ISSUE, encrypted only), reason? |
| CAPABILITIES | R→S (encrypted) | display{width,height,densityDpi,refreshHz}, codecs[{name,profiles[],hardware,lowLatency}], maxDecode{width,height,fps}, input{touchBar,imu,keys}, receiverModel, receiverOs, headViewportSupported |
| STREAM_START | S→R | codec, width, height, fps, bitrate, preset, sourceName, sessionShortId (binds UDP packets) |
| STREAM_STOP | S→R | reason |
| STREAM_FORMAT | S→R | codec, width, height, fps, csd0?, csd1?, sourceWidth, sourceHeight, rotationDegrees |
| VIEWPORT_SET | S→R | scale, centerX, centerY, fitMode (FIT/FILL/ACTUAL/CUSTOM) |
| PROFILE_SET | S→R | id, name, scale, offsetX, offsetY, fitMode, preferredStreamPreset |
| KEYFRAME_REQUEST | R→S | reason, lastFrameId |
| PING / PONG | both | sentNs / sentNs, receiverNs |
| STATS | R→S | packetsReceived, packetsLost, framesDelivered, framesDropped, framesDecoded, decodeLagFrames, reassemblyMs, decodeMs, renderMs, networkMs, fps, bitrateBps, clockOffsetNs |
| ERROR | both | code (ErrorCode name), message, recoverable |
| GOODBYE | both | reason |
