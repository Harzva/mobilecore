# MobileCore Runtime Contract v0.1

MobileCore 正式版不依赖 Termux。Termux 只作为 Android P0 验证后端。

正式 Runtime 的核心依赖是跨平台推理引擎，第一优先级为 llama.cpp + GGUF。

## 1. Runtime 抽象

所有平台后端必须实现同一组能力：

```text
RuntimeBackend
├── getBackendInfo()
├── scanModels(modelDir)
├── loadModel(modelPath, options)
├── unloadModel()
├── isModelLoaded()
├── chat(messages, options)
├── streamChat(messages, options)
├── getMetrics()
└── runBenchmark(profile)
```

## 2. 平台后端

```text
AndroidNativeLlamaBackend
  - Android App
  - JNI / NDK
  - llama.cpp
  - GGUF

IOSNativeLlamaBackend
  - iOS App
  - Swift / Objective-C++ bridge
  - llama.cpp
  - GGUF

TermuxProbeBackend
  - Android only
  - P0 validation only
  - external llama-server
```

## 3. RuntimeBackend 接口语义

### getBackendInfo()

返回后端信息：

```json
{
  "id": "android-llama-cpp",
  "platform": "android",
  "engine": "llama.cpp",
  "modelFormats": ["gguf"],
  "acceleration": ["cpu", "metal", "vulkan", "nnapi"],
  "status": "available"
}
```

### loadModel(modelPath, options)

加载模型。

```json
{
  "modelPath": "/models/qwen3-4b-q4.gguf",
  "contextLength": 4096,
  "threads": 4,
  "gpuLayers": 0
}
```

返回：

```json
{
  "ok": true,
  "modelId": "qwen3-4b-q4",
  "loadTimeMs": 6200,
  "memoryUsedMb": 4820
}
```

### chat(messages, options)

非流式生成。

### streamChat(messages, options)

流式生成，向 API 层输出 OpenAI-compatible SSE chunk。

### getMetrics()

返回最近推理指标：

```json
{
  "modelLoaded": true,
  "activeModel": "qwen3-4b-q4",
  "firstTokenMs": 730,
  "decodeTokensPerSecond": 18.6,
  "memoryPeakMb": 4820
}
```

## 4. Termux 的正式边界

Termux 不进入正式产品依赖链。

```text
Termux = P0 validation backend
llama.cpp = MobileCore primary runtime
```

允许保留开发者模式：

```text
Use external OpenAI-compatible endpoint
```

这可以让高级用户继续接 Termux llama-server、Ollama、LM Studio、远程服务器等。

## 5. 第一版实现目标

v0.1 implementation skeleton 先实现：

```text
- RuntimeBackend 接口文档
- Android native 后端占位
- iOS native 后端占位
- API server contract
- MobileCode provider preset
- P0 Termux bridge 兼容说明
```

后续再进入真实 Android/iOS 工程实现。
