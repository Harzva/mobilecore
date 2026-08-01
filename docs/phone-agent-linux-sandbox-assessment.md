# Phone Agent And Linux Sandbox Assessment

Date: 2026-06-26

This note answers whether MobileCore can borrow MobileCode's Alpine Linux Sandbox direction to deploy `PhoneBuddyAI/PhoneBuddy-4B` and Baidu `Unlimited-OCR`.

## Short Answer

MobileCore should borrow the Linux Sandbox architecture, but not as the first path for local PhoneBuddy-4B or Unlimited-OCR inference.

The useful part is the runtime packaging pattern:

- checksum-verified rootfs/bootstrap assets;
- app-owned storage;
- foreground service plus typed task API;
- capability reporting;
- explicit evidence for every runtime action.

The blocked part is model execution. PhoneBuddy-4B and Unlimited-OCR are not small Android-native GGUF text models. They need a multimodal Python/PyTorch/vLLM/SGLang class runtime with BF16 weights, vision processors, and GPU-oriented kernels. An Alpine rootfs inside Android can host scripts and probes, but it does not by itself provide CUDA, enough memory headroom, or a MobileCore-compatible VLM backend.

## Current MobileCore Runtime Fit

MobileCore Android currently has three practical local lanes:

1. `llama.cpp` JNI for single-file GGUF text models.
2. ML Kit OCR for on-device OCR.
3. ONNX Runtime Android / TensorFlow Lite for small vision probes and classifiers.

Those lanes are good for:

- GGUF LLM smoke tests;
- Qwen/Gemma/Phi small text baselines;
- OCR through ML Kit;
- RapidOCR / PP-OCR-style ONNX exploration;
- compact CLIP / MNIST / CIFAR10-style probes.

They are not enough for:

- Hugging Face safetensors VLM checkpoints;
- `qwen3_5` / Qwen3.5-VL model classes;
- BF16 multimodal generation;
- SGLang/vLLM GPU serving;
- large context OCR generation with CUDA-oriented kernels.

## PhoneBuddy-4B Status

Current public metadata for `PhoneBuddyAI/PhoneBuddy-4B` shows:

- pipeline: `image-text-to-text`;
- library: `transformers`;
- model type: `qwen3_5`;
- architecture: `Qwen3_5ForConditionalGeneration`;
- format: sharded `safetensors`;
- precision: BF16;
- parameters: about 4.54B;
- repository storage: about 9.1 GB;
- tags include `vision-language`, `qwen3.5-vl`, `phone-agent`, and `tool-use`.

This means it should stay in `android-app/model-downloads/phone-agent-catalog.tsv`, not in the GGUF catalog. It cannot be loaded through `/mobilecore/model/load` until one of these exists:

- a compatible GGUF/text-only conversion that actually passes `llama.cpp`;
- a Qwen3.5-VL Android runtime with tokenizer, processor, image tower, projector, and generation loop;
- a remote vLLM/SGLang/OpenAI-compatible provider adapter.

`PhoneBuddyAI/PhoneBuddy-0.8B` is the better probe candidate, but it is still a VLM/safetensors runtime question rather than a normal GGUF download.

## Unlimited-OCR Status

Baidu `Unlimited-OCR` is also not a first-step Android local model for MobileCore. Its README currently documents:

- Transformers inference tested with Python 3.12, CUDA 12.9, torch, torchvision, transformers, Pillow, PyMuPDF, and BF16 CUDA execution;
- SGLang serving through `python -m sglang.launch_server`;
- long context settings such as `context-length 32768`;
- PDF conversion to images before multimodal parsing.

For MobileCore, that suggests two routes:

1. Remote OCR provider: run Unlimited-OCR on a Mac/Linux/GPU host and expose an OpenAI-compatible or MobileCore-compatible endpoint.
2. Native mobile OCR lane: keep ML Kit as the default and evaluate RapidOCR / PP-OCR ONNX as the next local OCR upgrade.

Do not position Unlimited-OCR as deployed inside the Android APK until a real Android-compatible backend exists.

## What To Borrow From MobileCode

MobileCode's Alpine Linux Sandbox work is valuable as a runtime-management pattern. The most useful pieces for MobileCore are:

- `LinuxSandboxProvider` style capability model: installed, ready, arch, package packs, tool availability.
- `LinuxSandboxRunner` style setup: manifest URL, SHA-256 verification, rootfs extraction, app-private home/tmp directories.
- Typed tasks instead of raw shell: `probe_model_metadata`, `download_model_manifest`, `remote_provider_health`, `ocr_preprocess_pdf`, `vision_fixture_probe`.
- Evidence-first responses: status, reason, stdout/stderr snippets, elapsed time, sanitized paths.
- Reset/uninstall UX for large runtime artifacts.

MobileCore should not borrow:

- arbitrary raw shell access for model-controlled actions;
- broad Termux permissions or terminal semantics;
- heavyweight Python model serving inside the default APK path;
- any default claim that Linux Sandbox means local VLM inference is supported.

## Recommended Architecture

Use three separate provider lanes.

```text
MobileCore APK
  -> Local LLM Provider
       llama.cpp JNI
       GGUF only

  -> Local Vision Provider
       ML Kit OCR
       ONNX Runtime Android
       TensorFlow Lite

  -> External / Sandbox Provider
       Alpine typed tasks
       remote server probes
       model metadata checks
       optional preprocessing
       no default raw shell
```

For PhoneBuddy and Unlimited-OCR specifically:

```text
MobileCore UI
  -> Phone Agent catalog entry
  -> compatibility probe
  -> if remote endpoint configured:
        OpenAI-compatible / custom adapter
     else:
        runtime-blocked with exact reason
```

## Next Implementation Slice

1. Add a `RuntimeProvider` boundary for non-GGUF phone agents.
2. Add a `RemoteVlmProvider` that can call an OpenAI-compatible endpoint without storing secrets in repo files.
3. Add a `SandboxProbeProvider` that can run metadata-only checks when Linux Sandbox is available.
4. Keep `/mobilecore/model/load` GGUF-only.
5. Keep `/vision/ocr` ML Kit-first, then add RapidOCR / PP-OCR ONNX as the local OCR upgrade path.
6. Add smoke evidence:
   - PhoneBuddy-4B local GGUF load: blocked with `unsupported_format`;
   - PhoneBuddy remote endpoint health: pass/fail with sanitized URL;
   - Unlimited-OCR local Android: blocked with `unsupported_runtime`;
   - Unlimited-OCR remote endpoint: pass/fail;
   - ML Kit OCR: local pass/fail.

## Product Truth

The correct claim is:

MobileCore can manage phone-agent and OCR candidates, probe compatibility, run small local GGUF/vision models, and route heavy VLM/OCR workloads to a configured external runtime. MobileCore cannot yet deploy PhoneBuddy-4B or Unlimited-OCR fully inside the current Android local runtime.
