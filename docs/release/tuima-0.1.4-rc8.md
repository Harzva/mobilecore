# TuiMa 0.1.4-rc8

This prerelease supersedes the rejected rc7 tag. It keeps the MobileCore-owned Qwen2.5-Omni lifecycle surface and fixes version truth so the Android package and `/health` report the same generated build version.

## Changes

- Keep live memory, app-private storage, Wi-Fi, pinned artifact, license, verification, load, cancel, and uninstall state on the Android “本地多模态” screen.
- Keep non-persisted one-use consent after publisher, non-official-GGUF, source-declared/not-legally-reviewed license, download-size, and Wi-Fi-only disclosure.
- Return current resource values before install and keep `pair_verified` separate from runtime `loaded`.
- Inject `BuildConfig.VERSION_NAME` into the loopback API instead of maintaining an independent hard-coded service version.
- Assert through Android instrumentation that `/health.version` equals the installed build version.

## Published evidence

- Release: [TuiMa 0.1.4 RC8](https://github.com/Harzva/mobilecore/releases/tag/v0.1.4-rc8)
- Tag commit: `3ef9b2749697ee3398a06a58fdc1ab1311ca3fef`
- Final tag build: [GitHub Actions run 31140544846](https://github.com/Harzva/mobilecore/actions/runs/31140544846), `upload_signed=true`
- APK SHA-256: `e82a5f784cb3730f321126c059c40e43ac4489a756bb185d3d71c89d8d36535f`
- AAB SHA-256: `1f20e249176ef63c00123652a7c44669e233fbacf4a08bd22d0c1dea973dd6a8`
- Upload certificate SHA-256: `6d7661f82f2e6d415e8276b61526287ed373ee5b992cac7ea57f1e9f7a192cb0`
- The final APK update-installed over rc7, cold-launched in 575 ms, returned `version=0.1.4-rc8` from `/health`, preserved the same three local model entries, and showed no fatal marker in the verification log window.
- Local validation passed 155 JVM tests, lint, and APK/test-APK assembly. An isolated Android 16 emulator passed both targeted API instrumentation tests, including the generated-build-version assertion.

Physical Qwen2.5-Omni image/audio, peak memory, sustained thermal, transfer cancellation, and background recovery remain open gates. MobileCore continues to have no Phone Use, login, credential, click, approval, or transaction authority.
