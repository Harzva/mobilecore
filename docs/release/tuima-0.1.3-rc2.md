# TuiMa 0.1.3-rc2 Release Candidate

## Product decision

TuiMa is packaged as a standalone Android application. MobileCore remains its on-device runtime and loopback OpenAI-compatible API. The benchmark exposes two equivalent score surfaces:

- canonical score: 0–1000
- headline score: canonical score × 1000, displayed as 0–1,000,000 TuiMa

## Frozen benchmark identity

- spec: `tuima-llm-benchmark-v2`
- score algorithm: `tuima-score-v2`
- Android comparison population: `android-arm64-v8a`
- reference model: `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
- file: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- file size: `491400032` bytes
- model SHA-256: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`
- model license: Apache-2.0
- manifest SHA-256: `45e1ce81a9154dbf6e369e60f75f94a60399a9c334dea47af5ffb15c540d709e`

## Implemented in RC2

- Quick, Standard, and Stress profiles
- five score dimensions: inference, responsiveness, memory, sustained performance, stability
- battery, charging, battery-temperature, Android thermal, RAM, and free-storage telemetry
- preflight gates for battery, charging, heat, storage, model integrity, prompt integrity, runtime health, and concurrent runs
- typed invalid reports for preflight, timeout, OOM, model, runtime, cancellation, native crash, metrics, charging changes, service restart, and upload failures
- persistent v2 reports with `/v1/benchmark/latest` and `/v1/benchmark/reports`
- OpenAI-compatible JSON and SSE streaming responses, including usage and MobileCore metrics
- native llama.cpp cancellation used by the UI cancel action and HTTP timeout handling
- MobileCode `TuiMa Local` provider, 15-second readiness status, explicit switching, streamed chat, and cloud-transport fallback before any cloud text is emitted
- API level 35 Android target, APK/AAB candidate build, optional secret-backed upload signing, and CI artifact checksums
- physical-device acceptance script covering benchmark report, battery, thermal, RAM, storage, permissions, logs, and screenshot evidence

## Verification completed

- Benchmark v2 JVM tests pass.
- Android lint passes with no reported issues.
- Debug and release APK builds pass for `arm64-v8a`.
- Release candidate installs and cold-starts on an Android ARM64 API 36 emulator without a fatal exception.
- Frozen model loading and native inference were exercised on two emulator tiers. A slow 2-core tier correctly reached the typed timeout; the final 4-core ARM64 release completed a valid Quick run at 108.844 tok/s with a 924 canonical / 924000 headline score.
- The API 35 candidate passed the automated device script on the 4-core ARM64 emulator with a valid 908 canonical / 908000 headline report; the script deliberately marks this as non-physical evidence. The upload-signed artifact was separately verified with `apksigner`.
- Charging preflight was exercised and persisted as a `preflight_blocked` report exposed by the latest-report API.
- MobileCode provider and hybrid-routing tests pass.
- MobileCode Android debug, iOS simulator, and unsigned iOS device builds pass.

## Release boundaries

- Production upload-signed APK SHA-256: `5f3ad5d769ce929788174bb24080bfda12adb137d604fbe74986caa2c8135123`.
- Production upload-signed AAB SHA-256: `d62e9fbdc73e601f4ec49506a8c1979e91f5789578e3166cd44f62198b5d1753`.
- Upload certificate SHA-256: `6d7661f82f2e6d415e8276b61526287ed373ee5b992cac7ea57f1e9f7a192cb0`.
- The durable upload key is stored outside Git and its four signing inputs are configured as GitHub Actions secrets. Play publication still requires Play Console app/store credentials and enrollment in Play App Signing.
- A valid Standard score still needs a representative physical Android device run with the frozen model, disconnected from charging and below the thermal gate.
- Anonymous shared leaderboard upload remains opt-in and requires a production backend before enabling public comparison.
- TuiMa itself is Android-first in RC2. MobileCode compiles on iOS with local-network permission, but there is no separately shipped iOS TuiMa runtime in this candidate.
