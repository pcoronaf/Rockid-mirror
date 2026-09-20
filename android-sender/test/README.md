# Sender on-device tests

Unit tests run on the JVM: `./gradlew testDebugUnitTest` (state machine, geometry planner,
profiles/viewport controller, adaptive controller, latency tracker).

## Task B acceptance (encoder soak, no network required)
1. Install the debug APK, open the app, connect to `tools/mock-receiver` (or the glasses).
2. Tap **Test pattern** (or **Mirror phone**) and run `test/soak-encoder.sh 10`.
3. Pass criteria: no `FATAL EXCEPTION`, no projection `SecurityException`, PSS flat within
   ±10 % after the first minute, fps at the preset target, `encode_ms` stable.

## On-device matrix (spec)
Galaxy S25 → Rokid Glasses, plus mock receiver for network cases:
same 5/6 GHz Wi-Fi; Galaxy hotspot; weak Wi-Fi (walk away); Internet unavailable (airplane +
Wi-Fi); receiver restart mid-stream; phone rotation; selected app rotation; lock phone while
streaming (projection must stop, state returns to Ready); stop projection from the system UI.
