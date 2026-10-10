# Computer mode

Computer mode turns the glasses into a second screen that runs real apps, with the phone as a
trackpad — a phone-only "desktop", no PC involved. It is the foundation step: pairing, the shell
helper, one app on a glasses-sized display, a working click, and the phone as a trackpad. The
on-screen keyboard, the app switcher, big-screen/head-pan mode, and the secure-screen opt-in build
on top of it and are not part of this step.

## Why it needs more than an ordinary app can do

Extended-screen mode (`ExtendedDisplayController`) already makes a second display, but with
`OWN_CONTENT_ONLY` it can only show windows this app owns — Android refuses to launch *other* apps
onto a display an ordinary app created, and refuses input injection there. Those two abilities
require a *trusted* display and the INJECT_EVENTS permission, both of which belong to the `shell`
user. Computer mode borrows the shell identity through the phone's own Wireless debugging, for
exactly those calls, and nothing else.

## How it fits the existing pipeline

Computer mode is just a new frame source. The trusted display renders into the **same** encoder
input surface the mirror and extended modes use, so the existing encode → encrypt → send → decode
path and the glasses app are unchanged. No new wire messages; the stream is ordinary H.264.

```
Wireless debugging (phone, 127.0.0.1)
  │  Kadb pair + connect
  ▼
app_process  ──►  ShellHelperMain (shell uid)
  │  publishes Binder via getContentProviderExternal + call
  ▼
ShellPrivilegeProvider (app, SHELL_UID gate)  ──►  ShellBridge
  │                                    │
  │ forward "display" transactions     │ forward "am"/"input" commands
  ▼                                    ▼
ComputerDisplayController            ComputerControl
  creates TRUSTED virtual display      am start --display <id> <app>
  into the mirror encoder surface      input -d <id> tap/swipe/keyevent
  ▼
existing encoder → transport → glasses
```

## Setup (on the phone only)

1. **Developer options → Wireless debugging → on.** (Requires being on a Wi-Fi network — the same
   one the glasses use is fine.)
2. In the app's **Computer mode** card, open **Pair device with pairing code** on the phone, and
   type the shown **pair port** and **6-digit code** into the card, then **Pair**.
3. Type the **connect port** from the Wireless debugging screen (or leave blank to auto-detect over
   mDNS) and **Connect**. The helper launches and connects back.
4. Connect to the glasses as usual, then **Start computer** and pick an app.

The ADB identity is stored in app-private storage, so pairing survives app restarts. After a phone
reboot, Wireless debugging must be switched on again and **Connect** re-run (no re-pairing).

## Input

The phone's existing mouse pad drives a cursor (shown on the glasses via the usual pointer
message). In computer mode a tap/long-press/scroll is injected onto the trusted display with the
shell `input` command, which works for any app without an accessibility service. This is reliable
but not yet low-latency or continuous; a direct pointer-injection path (hover, drag, wheel) is a
later step.

## Limits and risks (foundation)

* Hidden-API dependent (same ones scrcpy uses); an OEM/OS update can change behavior. The display
  creation tries a full trusted+decorated configuration and degrades flag by flag on refusal.
* Confirmed working on this exact phone is a device test, not a guarantee from code: the S25 is not
  on Dextop's tested list, though same-software Samsung devices are.
* `am start --display` relies on the app having a launchable activity; some apps follow the user
  back to the phone display.
* Secure-display is intentionally off — `FLAG_SECURE` windows stay black on the glasses. See
  `docs/security.md`.

## Attribution

The privileged runtime (provider handshake, SHELL_UID gate, transaction forwarding, `app_process`
launch, trusted-display reflection + flag ladder) is adapted from Dextop (GPL-3.0). Pairing uses
Kadb (Apache-2.0). The design follows scrcpy's new-display/input approach (Apache-2.0). See
`NOTICE`.
