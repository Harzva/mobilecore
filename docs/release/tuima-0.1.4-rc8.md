# TuiMa 0.1.4-rc8

This candidate supersedes the rejected rc7 tag. It keeps the MobileCore-owned Qwen2.5-Omni lifecycle surface and fixes version truth so the Android package and `/health` report the same generated build version.

## Changes

- Keep live memory, app-private storage, Wi-Fi, pinned artifact, license, verification, load, cancel, and uninstall state on the Android “本地多模态” screen.
- Keep non-persisted one-use consent after publisher, non-official-GGUF, source-declared/not-legally-reviewed license, download-size, and Wi-Fi-only disclosure.
- Return current resource values before install and keep `pair_verified` separate from runtime `loaded`.
- Inject `BuildConfig.VERSION_NAME` into the loopback API instead of maintaining an independent hard-coded service version.
- Assert through Android instrumentation that `/health.version` equals the installed build version.

## Acceptance rule

The rc8 Release may be created only after the final tag APK is upload-signed, update-installs over the existing TuiMa package, preserves the three existing local model entries, cold-launches without fatal markers, and returns `version=0.1.4-rc8` from `/health` after the service starts.

Physical Qwen2.5-Omni image/audio, peak memory, sustained thermal, transfer cancellation, and background recovery remain open gates. MobileCore continues to have no Phone Use, login, credential, click, approval, or transaction authority.
