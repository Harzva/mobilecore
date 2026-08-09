# TuiMa 0.1.4-rc9

This prerelease turns MobileCore's model catalog into a trusted installation surface and adds a real local text-to-photo search path. It also closes exact runtime-identity, lifecycle ownership, media-memory, and Unicode boundaries found during final review.

## Changes

- Install eligible Mobile Model Playground artifacts from pinned Hugging Face revisions through storage preflight, resumable `.part` transfer, exact byte count, full SHA-256, same-directory atomic placement, runtime loading, and ownership-gated uninstall.
- Distinguish not downloaded, downloading, verifying, installed, loaded, checksum failure, and source mismatch in the UI. A same-name file is never treated as trusted without its complete digest.
- Bind loaded models and multimodal projectors to canonical internal paths; duplicate public IDs fail closed, and destructive operations share the same runtime ownership gate as inference and model switching.
- Harden the legacy Qwen2.5-Omni installer with controlled HTTPS redirects, range validation, exact bytes, full SHA-256, and atomic-only placement.
- Add paired CLIP ONNX image/text inference, a private incremental MediaStore index, local thumbnails, lifecycle-aware session release, bounded image/tokenizer/index memory, and Android 14 partial-photo selection.
- Preserve standard UTF-8 JSON and JNI strings across Chinese, emoji, skin-tone modifiers, and supplementary-plane characters.
- Keep upstream publisher, converter, distribution endpoint, license review, and emulator/physical validation evidence separate in the Playground catalog.

## Published artifacts

- Release: [TuiMa 0.1.4 RC9](https://github.com/Harzva/mobilecore/releases/tag/v0.1.4-rc9)
- Direct APK: [tuima-release.apk](https://github.com/Harzva/mobilecore/releases/download/v0.1.4-rc9/tuima-release.apk)
- Tag commit: `1680cfccee71704ec7c0a116c2b7fff9032e8651`
- Final tag build: [GitHub Actions run 31322358946](https://github.com/Harzva/mobilecore/actions/runs/31322358946), `upload_signed=true`
- APK: 42,568,241 bytes; SHA-256 `c883eaf359ce3f3e4eb11d33f9a2c6b80a411d9f3878e7f9bd33b759495e39fe`
- AAB: 28,621,963 bytes; SHA-256 `809ed7ddbe932a1f8eb2c5eef8a381144b48e82f3d145fe2f706da4846ba0ed7`
- Upload certificate SHA-256: `6d7661f82f2e6d415e8276b61526287ed373ee5b992cac7ea57f1e9f7a192cb0`
- Package identity: `com.mobilecore.app`, version code 13, version name `0.1.4-rc9`, minimum SDK 26, target SDK 35

The release assets were downloaded from the tag run into a clean temporary directory. `SHA256SUMS` passed for both files, `apksigner` verified the APK v2 signature, and the APK and AAB reported the same TuiMa Upload certificate.

## Release APK smoke

The upload-signed APK was freshly installed on an isolated, read-only `Pixel_7_API_36` ARM64 AVD rather than replacing the existing debug-signed QA app and its data. The Android 16 emulator reported:

- successful streamed install and cold launch in 2,503 ms;
- `versionCode=13` and `versionName=0.1.4-rc9`;
- a foreground `dataSync` `MobileCoreService` after the visible in-app API action;
- `/health.status=ok`, `version=0.1.4-rc9`, `runtime=llama.cpp`, `backend=cpu`, and no loaded model on the fresh profile;
- no MobileCore FATAL, ANR, or AndroidRuntime process marker in the verification window.

This verifies the exact published APK's install, launch, service, package version, and local health surface. It is not a physical-device performance result.

## Trusted Playground installer evidence

The registered Harzva conversion is [Harzva/mobilecore-qwen3-0.6b-gguf](https://huggingface.co/Harzva/mobilecore-qwen3-0.6b-gguf) at immutable revision `00b8b574c0cba5df1aa04971f179a7d29d828910`:

- artifact `qwen3-0.6b-q4_k_m.gguf`;
- 484,220,160 bytes;
- SHA-256 `18ea1f301079bba6391ab6d455c0c8565fd5a3214075eb2cd9daf351dedc719b`;
- upstream `Qwen/Qwen3-0.6B@c1899de289a04d12100db370d81485cdf75e47ca`;
- Harzva conversion with pinned `llama.cpp@fd1a05791da1c06dc8bebdd537aa5212831d7cf6`, Q4_K_M, no imatrix, Apache-2.0.

The emulator's public network path did not complete this Hugging Face transfer. A complete resumable `.part` was therefore seeded through ADB, after which only the production installer performed storage preflight, exact bytes, full SHA-256, atomic placement, trusted health identity, llama.cpp loading, inference, ownership-gated unload, and deletion. This is not reported as an emulator network-download pass. Anonymous public download and the fixed artifact digest are independently verified by the [Mobile Model Playground pipeline run 31315686773](https://github.com/Harzva/mobile-model-playground/actions/runs/31315686773).

On the Android 16 ARM64 emulator, the final-commit model lane preserved Chinese, emoji, and `𠮷` without U+FFFD, with 740 ms first-token latency, 48.6692 decode tok/s, 3,367 ms total time, and a reported 456 MB peak. The ownership gate rejected a concurrent uninstall as `runtime_busy`; after loading completed, uninstall confirmed the runtime was empty before deleting the artifact. These are emulator-only numbers.

## Local gallery-search evidence

The tested identity was `onnx-community/clip-vit-base-patch16-ONNX`, paired image/text encoders, 512-dimensional embeddings, combined artifact SHA-256 `047ea6bcdda8b9f5b8fe9c04aa86106ef32f58731831d847eaee7dfc1f3b7046`. ONNX Runtime Android 1.18.0 used `CPUExecutionProvider`; no GPU, NNAPI, or NPU result is claimed.

| Oxford-Pets lane | Samples / queries | R@1 | R@5 | MRR@100 | Indexing | Search | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Smoke | 37 / 37 | 78.38% | 94.59% | 85.68% | 10,533 ms | 1,845 ms | 12,456 ms |
| Pilot | 370 / 37 | 81.08% | 100.00% | 88.74% | 95,060 ms | 3,431 ms | 98,599 ms |

Both reports identify `sdk_gphone64_arm64`, API 36, `arm64-v8a`, and `physical_device_claim=false`. They also passed random-baseline and top-1 diversity sanity gates. The app-private index stores embeddings and metadata rather than raw image bytes, and search queries are neither persisted nor uploaded.

Evidence files:

- [trusted install/load/infer/uninstall](../evidence/playground-install/qwen3-0.6b-rc9-emulator.json)
- [Oxford-Pets 37-image smoke](../evidence/gallery-search/oxford-pets-gallery-smoke-emulator.json)
- [Oxford-Pets 370-image pilot](../evidence/gallery-search/oxford-pets-gallery-pilot-emulator.json)

## Verification and boundaries

- Local final verification passed 280 JVM tests with two conditional skips, lint, ARM64 native/APK assembly, and AndroidTest APK assembly.
- The public-safe diff passed Semgrep secret scanning, high-confidence credential/private-path checks, artifact-integrity checks, JSON evidence parsing, and whitespace checks.
- The bundled MobileCore catalog is byte-identical to the Playground export, SHA-256 `d819c908a75b9110d7104d5698621d8bb07aefb11c2cbabf21a42c2e9f531cdd`.
- [Mobile Model Playground](https://github.com/Harzva/mobile-model-playground) commit `7fd2ccfc135cb82344fc8dc5035ab9ffed84324f` supplies the validated release pipeline and welcomes external Issues and pull requests.
- No physical Android device was available, so phone speed, peak PSS/RSS, thermal behavior, and sustained-runtime claims remain open.
- The gallery product path is CLIP-only. A closed G2D/small-VLM candidate-verifier contract exists, but Agentic reranking is not enabled or advertised as a working product capability.
- Qwen2.5-Omni GGUF remains text/image/audio input to text output only. Video input, speech output, and complete any-to-any behavior are not claimed.
- This prerelease was not submitted to Google Play.
