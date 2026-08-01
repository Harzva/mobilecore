# ADR 0001: Keep MNN Qwen2.5-Omni as a separate P1 provider

- Status: Proposed / probe not complete
- Decision scope: Android on-device Qwen2.5-Omni-3B speech output

## Context

The P0 llama.cpp/libmtmd path reuses MobileCore’s existing GGUF runtime and supports text, image, and audio input with text output. Its selected ggml-org pair does not provide video input or speech generation.

The [Qwen2.5-Omni upstream project](https://github.com/QwenLM/Qwen2.5-Omni#deployment-with-mnn) recommends an MNN edge deployment and links 3B/7B MNN packages. Its published mobile table reports a 3B peak memory of 3.6 GB, with Thinker, Talker, and Code2Wav timings on Snapdragon 8 Gen 1 and 8 Elite. The [MNN Android Chat history](https://github.com/alibaba/MNN/blob/master/apps/Android/MnnLlmChat/README.md) says Qwen Omni 3B/7B support includes an audio-output switch.

These upstream claims justify a technical probe. They are not MobileCore device evidence, asset inventory, or a completed integration.

## Decision

MNN will be an independent provider, `mnn-qwen2.5-omni-3b`, with its own runtime adapter, artifact manifest, capability probe, loader, metrics, and failure mapping. It will not be hidden behind the llama.cpp backend name or reuse the GGUF/mmproj manifest.

The current source includes only `MnnOmniProvider` and `UnavailableMnnOmniProvider`. The latter reports every operational modality as false with reason `mnn_p1_not_integrated`; therefore health cannot accidentally advertise speech output before integration.

## Comparison

| Decision factor | llama.cpp/libmtmd P0 | MNN P1 candidate |
| --- | --- | --- |
| Existing MobileCore reuse | High | Low; new native/runtime integration |
| Asset form | Exact main GGUF + mmproj, 3.64 GB total | Multi-file MNN package; exact revision, inventory, hashes, and total size still must be captured |
| Inputs currently targeted | Text, image, audio | Validate text, image, audio first; video only if the exact Android package proves it |
| Outputs | Text only | Text + speech is the reason for the probe |
| Published 3B peak memory | Not measured for this Android pair | Upstream reports 3.6 GB; MobileCore must reproduce it |
| Product status | P0 runtime work | Unavailable P1 contract only |

## Technical validation plan

1. Pin an MNN runtime release and the exact official-linked 3B package revision. Inventory every model/tokenizer/audio asset with byte size, SHA-256, source, and license review. No “model size” based on a single weight file.
2. Implement `MnnOmniProvider` in a separate native library/Gradle module. Do not add conditional branches to the llama.cpp loader.
3. Run deterministic text→text, image→text, audio→text, and text→speech fixtures. Video remains unavailable unless a fixture proves the exact package and Android adapter.
4. Validate speech duration, non-silence, sample rate, intelligibility, cancellation, repeated-run diversity, and cleanup. A generated file alone is not a quality pass.
5. Record cold/warm load, TTFT, text tok/s, Talker and Code2Wav latency/RTF, peak PSS/RSS, asset/storage total, battery delta, temperature, and 20-minute sustained stability. Keep emulator control-flow evidence separate from physical-device evidence.
6. Exercise at least one 8 GB and one 12 GB RAM Android device, a Snapdragon 8 Gen 1-class baseline and a current flagship if available, CPU fallback, and any selected GPU backend. Record ABI/SoC/Android/MNN revision for every result.

## Productization gate

Ship the MNN provider only if all conditions pass:

- exact package manifest, consent, Wi-Fi-only download, verification, cancel, update, and uninstall are complete;
- text/image/audio input and text/speech output pass quality sanity fixtures without fixed-output collapse;
- peak PSS leaves at least 25% of physical RAM available and the OS does not kill the app during a 20-minute session;
- speech begins within the product latency budget defined before the probe, real-time factor and thermal drift are recorded, and no tested device enters severe thermal status;
- app/native size delta and total downloaded assets are disclosed in UI before consent;
- no user media or generated speech is logged or persisted beyond the user-controlled output lifecycle;
- failures map to the common typed contract and the provider remains independently disableable.

If any gate fails, retain llama.cpp for text/image/audio→text and keep speech output unavailable. Do not merge both implementations into an ambiguous “Omni backend.”
