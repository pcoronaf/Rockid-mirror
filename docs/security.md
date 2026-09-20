# Security and privacy

Screen content is sensitive. Release requirements from the specification and how they are met:

| Requirement | Implementation |
|---|---|
| No cloud relay, works offline | LAN/hotspot only; DNS-SD discovery; no accounts, analytics or telemetry SaaS |
| Video not recorded / not on disk | Sender and receiver never write frames; only the desktop `mock-receiver --dump` (debug tool) does |
| Trusted receivers explicitly paired | 6-digit code shown on the glasses, typed on the phone; bitwise commit/reveal bound to an ephemeral ECDH transcript (`docs/protocol.md`); code single-use, 90 s TTL, burned on first wrong bit |
| Encrypted + authenticated transport | AES-256-GCM per direction on control; AEAD per datagram on video with the header as associated data; keys from HKDF over ECDH secret + transcript; new keys every connection |
| Unknown senders rejected | Credential lookup fails → pairing code required → nothing streams without the user reading the glasses. Second concurrent sender is refused with `RESOURCE_EXHAUSTED` |
| No captured data in logs | `MirrorLog`/`ReceiverLog` only log sizes, counters, timings, state names; pairing codes and secrets are never logged |
| Control messages carry minimal metadata | `sourceName` is "Phone"/"App"/"Test pattern"; no package names or titles |
| Secure credential storage | AES-GCM key in Android Keystore wraps the credential on both devices; backups/device transfer excluded |
| Visible mirroring indicator | Phone: ongoing foreground notification with Stop; glasses: white dot + status line while streaming |
| Stop when Android ends the projection | `MediaProjection.Callback.onStop` → immediate teardown, `STREAM_STOP`, UI to Ready |
| No FLAG_SECURE / DRM bypass | Not implemented; Android blanks protected content in the VirtualDisplay and the app never reads pixels |

## Mouse mode and touch injection

Mouse mode lets the control pad click on the phone while the wearer looks at the glasses. Android
gives a no-root app exactly one way to deliver a tap to another app: an accessibility service with
gesture dispatch. `PointerService` is the smallest possible one.

* Its config requests **no accessibility event types** and sets `canRetrieveWindowContent="false"`,
  so it cannot read the screen, its text, or what is typed. `onAccessibilityEvent` is empty and
  nothing in the class inspects the UI.
* It only replays gestures the user just made on the phone's own control pad, plus the Back, Home
  and Recents global actions. It never acts on its own.
* The user must enable it by hand in Accessibility settings; the app cannot grant it, and mouse
  mode works as a pointer (cursor only, no clicks) when it is off.
* Injection is refused unless the whole screen is being captured, because with a single captured
  app the cursor cannot be mapped to real screen coordinates.
* The cursor is drawn by the glasses overlay, not composited into the captured video, so it costs
  no bandwidth and never appears in the encoded stream.

This is a deliberate departure from the v0.1 non-goal "remote touch injection into arbitrary
Android apps", made at the product owner's request, and it is opt-in twice over: the user enables
the service, then switches the pad to Mouse.

## Threat model notes
* Passive LAN sniffing: sees ports, packet sizes/timing and the cleartext 40-byte headers (frame
  ids, timestamps), not content.
* Active on-path attacker during first pairing: must win 20 independent coin flips (2^-20) and
  each failure burns the code. Offline brute force is impossible because commitments are per bit
  and bound to the attacker-visible transcript `T`, which differs per key exchange.
* Stolen credential (rooted device): allows impersonating that phone to that receiver only;
  "Forget receiver" / "Forget all senders" revoke it.
* Replay: control counters are strictly increasing; video nonces are unique per session key.
* Not in scope: physical access to an unlocked phone, malicious apps with screen-reading permission.

## Release-build checklist
Debug overlays are gated on `BuildConfig.DEBUG`; signing keys live in a git-ignored
`keystore.properties`; run `./gradlew lint` and confirm no `Log` call prints payloads.
