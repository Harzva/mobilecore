# Qwen2.5-Omni-3B compatibility and artifact policy

Status: P0 engineering integration. This document reports capabilities per modality; it does not use “model loaded” as a synonym for multimodal compatibility.

## Capability boundary

| Route | Text input | Image input | Audio input | Video input | Text output | Audio output |
| --- | --- | --- | --- | --- | --- | --- |
| llama.cpp/libmtmd + ggml-org GGUF pair | Yes | Yes | Yes | No | Yes | No |
| MNN P1 provider in this repository | No — not integrated | No — not integrated | No — not integrated | No — not integrated | No — not integrated | No — not integrated |

The upstream Qwen2.5-Omni architecture can accept video and generate speech, but that does not make those modalities available through MobileCore’s GGUF route. The selected [ggml-org conversion repository](https://huggingface.co/ggml-org/Qwen2.5-Omni-3B-GGUF) explicitly describes text/audio/image input and text output, with video input and audio generation unavailable. The conversion points to the [original Qwen checkpoint](https://huggingface.co/Qwen/Qwen2.5-Omni-3B).

The repository is published by `ggml-org`; it must not be described as a “Qwen official GGUF.” Its source-declared license identifier is `qwen-research`. This records repository metadata, not a legal approval; the current review status is `source_declared_not_legal_reviewed`.

## Exact dual-artifact manifest

Pinned Hugging Face revision: `75f1b73b657a50f5092502799457ccb4a4a1f9df`.

| Role | File | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Main, Q4_K_M | `Qwen2.5-Omni-3B-Q4_K_M.gguf` | 2,104,931,648 | `4b0bd358c1e9ec55dd3055ef6d71c958c821533d85916a10cfa89c4552a86e29` |
| mmproj, Q8_0 | `mmproj-Qwen2.5-Omni-3B-Q8_0.gguf` | 1,538,031,328 | `4e6c816cd33f7298d07cb780c136a396631e50e62f6501660271f8c6e302e565` |

The pair is 3,642,962,976 bytes: about 3.64 decimal GB / 3.39 GiB, before app, cache, or runtime overhead. Therefore “2 GB complete multimodal” is false. Machine-readable copies live in:

- `android-app/app/src/main/assets/model-manifests/qwen2.5-omni-3b-ggml-org.json`
- `Qwen25Omni3bArtifacts` in the Android source.

The exact sizes and hashes were captured from the revision-pinned Hugging Face LFS/Xet metadata. No large model was downloaded during this change. Installation still recalculates SHA-256 over downloaded bytes before promotion, so registry metadata alone is never treated as local verification.

Pinned llama.cpp/libmtmd revision: `e1af89a6815737a5db132eee23a94a8ee58553e0`. This is the upstream merge commit for [llama.cpp PR #26262](https://github.com/ggml-org/llama.cpp/pull/26262), which fixes the Qwen2.5-Omni mmproj conversion regression. MobileCore does not follow a floating branch.

## Install lifecycle

`OmniArtifactInstaller` is a callable, app-private lifecycle service with these invariants:

1. `install(...)` requires explicit consent and an accepted displayed license ID; Wi-Fi-only is the default.
2. Preflight reports required/available storage and memory separately. Storage requires the complete pair plus a 512 MiB safety margin. The memory gate is provisionally the complete mapped pair plus 1 GiB headroom; it must be replaced by physical-device peak measurements before default product enablement.
3. Each file downloads to `<file>.part`, is bounded by pinned byte size, is hashed, and is promoted only after SHA-256 matches.
4. Cancellation deletes `.part`; uninstall removes both exact artifacts, both `.part` files, and verification metadata.
5. `loadVerifiedPair(...)` passes both paths to a runtime only when both artifacts have a valid stat-bound verification record.
6. `snapshot()` is suitable for health checks: it reports expected hashes and cached verification state without rehashing 3.64 GB on every request. A size or modification-time change invalidates the record. `verifyInstalledPair()` performs an explicit full rehash.

Android’s production environment probe uses app-private storage, available system memory, and `NetworkCapabilities.TRANSPORT_WIFI`. The manifest includes `ACCESS_NETWORK_STATE`; it does not request broad storage access.

The lifecycle service is wired to authenticated loopback-only `status`, `install`, `cancel`, `verify`, `load`, and `uninstall` endpoints. The install endpoint requires explicit consent plus the displayed source-declared license ID before its background worker can start. A dedicated in-app consent screen and physical-device evidence are still missing, so release documentation must not call Qwen2.5-Omni a default or broadly compatible model.

## Typed failures

The artifact layer exposes the required wire values `unsupported_modality`, `artifact_missing`, `checksum_mismatch`, `insufficient_memory`, `insufficient_storage`, `model_load_failed`, `projector_incompatible`, `projector_load_failed`, `media_too_large`, and `cancelled`. A missing or truncated pinned mmproj is rejected as `artifact_missing` or `checksum_mismatch` before native loading; a wrong model-family pairing is `projector_incompatible`; a verified projector rejected by libmtmd is `projector_load_failed`. Install-specific additions include `explicit_consent_required`, `license_acceptance_required`, `wifi_required`, `manifest_invalid`, `download_failed`, and `install_in_progress`.

## Evidence boundary

Unit coverage verifies the exact manifest, pair completeness, consent/license/Wi-Fi gates, independent storage and memory failures, `.part` verification, cancellation cleanup, load-pair gating, snapshot invalidation, typed projector failures, and uninstall. Android arm64 native compilation and an Android 16 emulator run also verify that the pinned llama.cpp revision loads a real Qwen3.5 0.8B GGUF/mmproj pair and completes an OpenAI-compatible image request through libmtmd. The bridge explicitly initializes the upstream `mtmd_input_text.text_len` field introduced after the previous pin; leaving it unset causes deterministic tokenize failure.

That emulator run is a compatibility smoke test for the shared libmtmd bridge, not evidence that the 3.64 GB Qwen2.5-Omni pair fits or performs acceptably on a phone. No Qwen2.5-Omni artifact was downloaded for this change. It does not prove Omni output quality, speed, peak PSS/RSS, temperature, or sustained stability.

Those runtime measurements must be collected separately for emulator and physical hardware. An emulator may validate API/JNI control flow but cannot substitute for physical-device performance, thermal, battery, or compatibility evidence.
