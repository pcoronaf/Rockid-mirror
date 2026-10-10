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

## Camera vision mode

Vision mode shows the glasses' own camera on the panel with the contrast lifted, and marks each
person facing the wearer with corner brackets.

* **The capture indicator is untouched.** The camera is opened through the ordinary Android API
  and nothing in this project reads, writes or suppresses the device's recording indicator.
  Whatever the glasses normally show while the camera is live, they show here.
* **Nothing is recorded.** Frames are read, used to draw the current picture and dropped. No
  frame is written to storage, and none is sent over the network or to the phone.
* **Nobody is identified.** The detector reports where a face is, never whose it is. No face
  data is stored, compared or accumulated, and face recognition is deliberately not implemented.
* **The model runs on the glasses.** The detector's model is bundled in the APK, so it needs no
  network and no Google Play services.
* **The user grants the camera.** The app requests the runtime permission when the mode is first
  turned on and never opens the camera otherwise.
* Vision mode and mirroring are mutually exclusive, so the panel is never showing the wearer's
  camera and a mirrored screen at the same time.

Recording and filming laws differ by country and by place. Wearing camera glasses that mark
people is lawful in many settings and not in others; that judgement sits with the wearer.

## Extended screen

Extended-screen mode creates a virtual display with `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`, so
it can only ever show what this app explicitly launches onto it. It captures nothing, which is
why it correctly needs no MediaProjection consent and no capture notification. Apps are listed
through a manifest `queries` declaration for launcher activities rather than QUERY_ALL_PACKAGES.
Launching another app onto a second display is subject to platform policy that has tightened over
releases; refusals are reported to the user rather than worked around.

## Computer mode (shell-level helper)

Computer mode runs real apps on a *trusted* virtual display sent to the glasses and injects input
onto it. Both require the `shell` identity, which an ordinary app cannot hold. The app therefore
pairs once with the phone's own Wireless debugging endpoint and launches a small helper through
`app_process`, which runs as `shell`. The helper hands a Binder back to the app
(`ShellPrivilegeProvider`, which accepts it only from `Process.SHELL_UID`); the app then forwards
system-service transactions and a few `am`/`input` commands through it. This is a real increase in
this app's effective privilege on the phone, granted deliberately by the user, and it is the one
place the app reaches beyond the standard, consent-gated capture path. It is documented rather than
hidden, opt-in, and off until the user pairs.

Boundaries that still hold:
* **No screen capture.** Computer mode never captures the phone's own display. The glasses show
  only the second display the app created and launches apps onto; the phone screen stays private.
* **The helper is minimal.** It forwards transactions and runs commands and nothing else. It holds
  no app state, inspects no screen content, and logs nothing about what passes through it. It is
  not exported to other apps: the provider gate rejects any caller that is not the shell user.
* **Pairing material stays on the device.** The ADB private key is kept in app-private storage,
  which is excluded from backup and transfer (`data_extraction_rules`), and never logged. It is the
  one long-lived secret computer mode adds.
* **Lifetime.** The helper dies with the shell session / on reboot; the app detects this
  (`ShellBridge.onDied`) and returns to the pairing prompt. Re-pairing is not needed because the
  stored key persists; reconnecting re-launches the helper.
* **Secure-display flag is NOT set.** The trusted display is created without a secure flag, so
  `FLAG_SECURE` windows (banking apps, etc.) stay blacked out on the glasses exactly as the
  platform intends. A user-facing opt-in to override this, with a warning, is a later step and is
  not part of this foundation; DRM-protected video (Widevine L1) stays black regardless.

This supersedes the earlier blanket statement that the app uses "Android standard APIs only on the
phone": computer mode is an explicit, opt-in exception, bounded as above.

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
