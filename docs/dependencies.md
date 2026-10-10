# Dependencies

Every dependency solves a specific need; no analytics, crash upload or cloud SDKs (spec rule).
Versions are pinned in `gradle/libs.versions.toml`.

| Name | Version | License | Purpose | Used by |
|---|---|---|---|---|
| Kotlin (stdlib, Gradle plugin, compose compiler) | 2.2.20 | Apache-2.0 | Language (2.2.x so the compiler reads Kadb's newer metadata) | all |
| kotlinx-coroutines (core/android/test) | 1.10.2 | Apache-2.0 | Structured concurrency, `StateFlow` | all |
| kotlinx-serialization-json | 1.8.1 | Apache-2.0 | Versioned JSON control messages, profile persistence, diagnostics export | protocol, sender |
| Android Gradle Plugin | 8.10.1 | Apache-2.0 | Build | sender, receiver |
| AndroidX core-ktx | 1.16.0 | Apache-2.0 | Framework extensions | sender, receiver |
| AndroidX activity-compose | 1.10.1 | Apache-2.0 | Compose host + activity result launchers | sender |
| AndroidX lifecycle (runtime, viewmodel-compose) | 2.9.0 | Apache-2.0 | Lifecycle-aware collection | sender |
| AndroidX DataStore preferences | 1.1.7 | Apache-2.0 | Profile / preset persistence | sender |
| Jetpack Compose BOM (ui, material3, tooling) | 2025.05.01 | Apache-2.0 | Phone UI | sender |
| JUnit | 4.13.2 | EPL-1.0 | Unit tests | all |
| ML Kit face detection (bundled) | 16.1.7 | Apache-2.0 | On-device person detection for the glasses' vision mode; model ships in the APK, so no network and no Play services | rokid-receiver/camera |
| JmDNS | 3.5.9 | Apache-2.0 | mDNS advertising for the desktop mock receiver only | tools/mock-receiver |
| Kadb | 2.1.4 | Apache-2.0 | Computer mode: pairs with and connects to the phone's own Wireless debugging endpoint | sender |
| spake2-java | 1.1.1 | Apache-2.0 | SPAKE2 key exchange for ADB pairing (transitive via Kadb; from JitPack, group `com.github.Flyfish233`) | sender |
| HiddenApiBypass | 6.1 | Apache-2.0 | Lets the shell-level computer-mode helper reach hidden display/input APIs | sender |
| Gradle | 8.14.3 | Apache-2.0 | Build tool (wrapper committed) | all |

Deliberately **not** used: WebRTC (not required by the fallback transport; may be revisited after
M0), androidx.security-crypto (Keystore AES used directly), any Rokid SDK artifact (see
`rokid-sdk-findings.md`; the Maven repo is only pre-configured).

Crypto comes from the platform JCA providers: EC P-256 ECDH, HMAC-SHA256, AES/GCM — available on
Android 5+ and every JDK 17.
