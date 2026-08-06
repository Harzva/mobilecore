# TuiMa 0.1.4-rc6

This candidate hardens MobileCore's Android background continuity while preserving the product boundary: MobileCore performs local inference; MobileCode owns orchestration, Phone Use, approvals, credentials, and ActionEvidence.

## Changes

- Reassert the explicitly typed `dataSync` foreground notification before model work on every service delivery.
- Avoid creating repeated foreground-service start obligations for visible, user-initiated refreshes.
- Report Android `background_restricted` in `/health` so clients can fail closed before local inference is interrupted.
- Reject a background-restricted device state in the redacted MobileCode background-link acceptance lane. The App only guides users to system Battery settings and never changes secure settings itself.

## Emulator evidence

- Final-tag GitHub Actions [run 31129912507](https://github.com/Harzva/mobilecore/actions/runs/31129912507) passed JVM tests, Android Lint, pinned llama.cpp bootstrap, and upload-signed APK/AAB builds from commit `1e791dfd71198b172dc4a4142c352e432c40eefa`.
- Official upload-signed APK SHA-256: `278a977f8813c97f75c58fa32b90790ce11cd9bbb4d15147e91ca73b036401dc`.
- Official upload-signed AAB SHA-256: `50217b010cc015590b6098ed536f7dcae543a6644158486a521163b190b403d2`.
- Android 16 ARM64 emulator with MobileCore `0.1.4-rc6` and MobileCode `0.1.77+67`.
- Real Qwen2.5 0.5B Q4_K_M model loaded through llama.cpp.
- The exact final-tag upload-signed APK was update-installed without deleting the real model. Its `/health` response reported `background_restricted=false`, then 40 authenticated polls over about two minutes while MobileCode remained resumed passed 40/40 (redacted artifact ID `20260806T232058Z`).
- Final state: foreground service true, both processes alive, MobileCode foreground true, background restriction false, process freezing false, and no FGS timeout, ANR, OOM, or SIGABRT safety finding.
- A controlled real-model request returned HTTP 200 and completed one inference with zero request failures. The retained counters report 15 prompt tokens, one completion token, 16 total tokens, and 6,183 ms total time; the prompt and response body were not retained.
- A deliberately background-restricted emulator state was rejected before the dual-app lane and then restored through the QA harness. This validates detection only; the App never changes the restriction itself.
- Evidence stores summaries and health metadata only; raw dumpsys, full logcat, prompts, model output, media, credentials, and local model paths are omitted.

## Remaining gates

This is emulator evidence, not a physical-device claim. A compatible physical Android device is still required for sustained thermal, low-memory, background recovery, and verified Qwen2.5-Omni image/audio acceptance. iOS background inference remains a separate platform implementation and validation task.
