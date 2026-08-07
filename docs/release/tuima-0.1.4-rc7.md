# TuiMa 0.1.4-rc7

This candidate adds a MobileCore-owned Qwen2.5-Omni lifecycle surface while preserving the product boundary: MobileCore performs local inference; MobileCode owns orchestration, Phone Use, approvals, credentials, transactions, and ActionEvidence.

## Changes

- Add an Android “本地多模态” screen for live memory, app-private storage, Wi-Fi, pinned artifact, license, verification, and runtime-load state.
- Require a non-persisted, one-use checkbox after showing the conversion publisher, non-official-GGUF notice, source-declared/not-legally-reviewed license state, 3.39 GiB download scope, and Wi-Fi-only policy.
- Expose install, cancel, full verify, load, and uninstall only when their lifecycle state permits them. A resource-blocked device receives only a recheck action.
- Return current required/available resource values from `/mobilecore/omni/status` before the first install attempt, plus separate `pair_verified` and runtime `loaded` booleans.
- Keep model files and paths private to MobileCore. The new screen has no click, login, credential, Phone Use, or transaction API.

## Local validation

- 155 JVM tests passed with zero failures, including lifecycle parsing, typed API failure retention, Wi-Fi-only blocking, pre-consent resource truth, pair verification versus runtime load, artifact failure precedence, and install cancellation projection.
- Android Lint passed.
- Android arm64 native build and Debug APK assembly passed.
- Isolated Android 16 ARM64 AVD: the visible screen started the service only after user action, then reported 1.12 GiB available memory versus a 4.39 GiB conservative gate, 4.43 GiB storage versus 3.89 GiB required, and no Wi-Fi.
- The device displayed “当前设备条件不足”, exposed only “重新检查设备条件”, and downloaded no Omni artifact.
- Final local rc7 debug APK was update-installed as version code 11 and cold-launched in 1,304 ms with no fatal marker. Its SHA-256 is `459d9d624b1b6586d51504a49926d1df8db38aab8e8378d4ca1949c2340fb3cc`; this is build evidence only and is not a release asset.
- Screenshots, raw UI dumps, logcat, and machine paths remain local QA artifacts and are not committed.

## Remaining gates

This is emulator control-path evidence, not a Qwen2.5-Omni runtime or physical-phone claim. A compatible physical Android device is still required for the complete verified pair, real image/audio quality, peak memory, sustained thermal behavior, cancellation under transfer, low-memory recovery, and background recovery. iOS local multimodal inference remains a separate implementation and validation task.
