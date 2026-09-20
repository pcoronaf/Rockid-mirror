# Receiver on-device tests

Unit tests (JVM): `./gradlew testDebugUnitTest` — decoder state machine, pipeline stats,
head-viewport controller; the shared reassembler/handshake tests live in `protocol/kotlin`.

## Task D acceptance (synthetic source, 10 minutes)
1. `adb install -r app/build/outputs/apk/debug/app-debug.apk` via the 5-pin dev cable
   (debugging enabled from the Hi Rokid app; see docs/rokid-sdk-findings.md).
2. `adb shell am start -n com.rokidmirror.receiver.debug/com.rokidmirror.receiver.app.ReceiverActivity`
3. From a PC on the same network: `tools/stream-generator --host <glasses-ip> --file test480p30.h264 --loop`
4. Record for 10 minutes, every 30 s: `adb shell dumpsys meminfo com.rokidmirror.receiver.debug | grep TOTAL`,
   `adb shell dumpsys cpuinfo | grep rokidmirror`, `adb shell dumpsys thermalservice | head`,
   plus the STATS lines printed by the generator (fps, lost, asm/dec/rnd ms, lag).
5. Pass criteria: continuous motion, fps ≈ 30, `lag` not growing, PSS flat, no thermal throttle.
   Fill in docs/m0-feasibility-report.md with the numbers.
