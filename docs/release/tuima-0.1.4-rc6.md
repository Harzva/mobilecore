# TuiMa 0.1.4-rc6

This candidate hardens MobileCore's Android background continuity while preserving the product boundary: MobileCore performs local inference; MobileCode owns orchestration, Phone Use, approvals, credentials, and ActionEvidence.

## Changes

- Reassert the explicitly typed `dataSync` foreground notification before model work on every service delivery.
- Avoid creating repeated foreground-service start obligations for visible, user-initiated refreshes.
- Report Android `background_restricted` in `/health` so clients can fail closed before local inference is interrupted.
- Reject a background-restricted device state in the redacted MobileCode background-link acceptance lane. The App only guides users to system Battery settings and never changes secure settings itself.

## Emulator evidence

- Android 16 ARM64 emulator with MobileCore `0.1.4-rc6` and MobileCode `0.1.77+67`.
- Real Qwen2.5 0.5B Q4_K_M model loaded through llama.cpp.
- 40 authenticated `/health` polls over about two minutes while MobileCode remained resumed: 40/40 passed.
- Final state: foreground service true, both processes alive, MobileCode foreground true, background restriction false, process freezing false, and no FGS timeout, ANR, OOM, or SIGABRT safety finding.
- Local debug-signed APK SHA-256: `02ff1fc353a0074d8b098c079925f3104f141fe10246b7c873993fa5a72228e1`. This hash is QA-only and must not be presented as the upload-signed GitHub release artifact.
- Evidence stores summaries and health metadata only; raw dumpsys, full logcat, prompts, model output, media, credentials, and local model paths are omitted.

## Remaining gates

This is emulator evidence, not a physical-device claim. A compatible physical Android device is still required for sustained thermal, low-memory, background recovery, and verified Qwen2.5-Omni image/audio acceptance. iOS background inference remains a separate platform implementation and validation task.
