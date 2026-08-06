# MobileCore API Contract

## 1. API 目标

MobileCore 对外暴露 OpenAI-compatible API，使 MobileCode、第三方 App、脚本、SDK 可以用统一方式调用 Android 本地模型。

原则：

```text
能兼容 OpenAI 就不自定义；
必须自定义时，放到 /mobilecore/* 命名空间。
```

## 2. 默认服务地址

```text
http://127.0.0.1:8080/v1
```

当前版本只监听 localhost，不提供 LAN 模式。多模态媒体、token 和请求正文不得通过非 loopback 地址暴露。

## 3. 鉴权

MVP 可使用固定 local key：

```text
Authorization: Bearer local
```

正式版应支持：

- 随机生成本地 token；
- token 轮换；
- App allowlist；
- LAN 模式单独 token。

## 4. GET /v1/models

### 请求

```http
GET /v1/models
Authorization: Bearer local
```

### 响应

```json
{
  "object": "list",
  "data": [
    {
      "id": "qwen3-4b-q4_k_m",
      "object": "model",
      "created": 1782000000,
      "owned_by": "mobilecore",
      "mobilecore": {
        "format": "gguf",
        "backend": "llama.cpp",
        "size_bytes": 2600000000,
        "quantization": "Q4_K_M",
        "context_length": 4096,
        "loaded": true
      }
    }
  ]
}
```

## 5. POST /v1/chat/completions

`messages[].content` 继续接受字符串，也可使用 OpenAI structured content 数组。当前只支持 `text`、`image_url`、`input_audio`，且每个请求最多一个图片或音频；输出仅为文本。远程 `http(s)` 媒体、非 app-controlled URI、路径穿越、超限 MIME/大小/音频时长均会以稳定错误码拒绝。video input 与 audio output 返回 `unsupported_modality`。

### 非流式请求

```json
{
  "model": "qwen3-4b-q4_k_m",
  "messages": [
    {"role": "system", "content": "You are a helpful local assistant."},
    {"role": "user", "content": "你好，介绍一下你自己。"}
  ],
  "temperature": 0.7,
  "max_tokens": 512,
  "stream": false
}
```

### 非流式响应

```json
{
  "id": "chatcmpl-local-0001",
  "object": "chat.completion",
  "created": 1782000000,
  "model": "qwen3-4b-q4_k_m",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "你好，我是运行在本机 MobileCore 上的本地模型。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 32,
    "completion_tokens": 24,
    "total_tokens": 56
  },
  "mobilecore": {
    "backend": "llama.cpp",
    "decode_tokens_per_second": 18.6,
    "first_token_ms": 730,
    "memory_peak_mb": 4820
  }
}
```

### 流式请求

```json
{
  "model": "qwen3-4b-q4_k_m",
  "messages": [
    {"role": "user", "content": "写一个简单的 Python hello world。"}
  ],
  "stream": true
}
```

### 流式响应

使用 SSE：

```text
data: {"id":"chatcmpl-local-0001","object":"chat.completion.chunk","choices":[{"delta":{"role":"assistant"},"index":0}]}

data: {"id":"chatcmpl-local-0001","object":"chat.completion.chunk","choices":[{"delta":{"content":"print"},"index":0}]}

data: {"id":"chatcmpl-local-0001","object":"chat.completion.chunk","choices":[{"delta":{"content":"('Hello, world!')"},"index":0}]}

data: [DONE]
```

## 6. GET /health

### 请求

```http
GET /health
```

### 响应

```json
{
  "status": "ok",
  "service": "mobilecore",
  "version": "0.1.4-rc5",
  "protocol": {
    "name": "mobilecore.local",
    "major": 2,
    "minor": 0,
    "min_client_major": 2,
    "max_client_major": 2
  },
  "model_loaded": true,
  "active_model": "Qwen2.5-Omni-3B-Q4_K_M",
  "quantization": "Q4_K_M",
  "runtime": "llama.cpp/libmtmd",
  "backend": "cpu",
  "capabilities": {
    "text_input": true,
    "image_input": true,
    "audio_input": true,
    "video_input": false,
    "text_output": true,
    "audio_output": false
  },
  "artifacts": {
    "main": {"digest_algorithm":"sha256","digest":"<expected>","present":true,"verified":true},
    "mmproj": {"digest_algorithm":"sha256","digest":"<expected>","present":true,"verified":true}
  },
  "preflight": {
    "memory": {"available_bytes":0,"required_bytes":0,"ok":true},
    "storage": {"available_bytes":0,"required_bytes":0,"ok":true},
    "ok": true
  }
}
```

能力布尔值表示当前加载 runtime 的能力，不是模型卡能力。GGUF 路线不得把 video 或 speech output 标为可用。

`protocol` 是 MobileCoreClient 控制协议，不等同于 App 版本。MobileCode 必须在模型加载、卸载、切换和推理前确认协议名称为 `mobilecore.local`、major 为 2，且客户端 major 落在服务端声明的支持范围内；不兼容时禁止继续控制并返回类型化错误。

`preflight` 同样绑定当前 runtime：普通文本 GGUF 使用当前模型文件大小加运行时上下文开销估算；只有已验证的 Omni 主模型与 projector 成对加载时，才返回 Omni 安装清单的内存/存储要求。普通 GGUF 未经过固定清单摘要校验时，`artifacts.main.verified=false` 且摘要为 `null`，不得伪报已验证。

## 7. GET /metrics

### 请求

```http
GET /metrics
Authorization: Bearer local
```

### 响应

```json
{
  "active_model": "qwen3-4b-q4_k_m",
  "backend": "llama.cpp",
  "uptime_seconds": 3600,
  "requests_total": 48,
  "requests_completed": 46,
  "requests_failed": 1,
  "inference_cancel_requests": 1,
  "inference_busy_rejections": 0,
  "last_decode_tokens_per_second": 18.6,
  "average_decode_tokens_per_second": 17.9,
  "last_first_token_ms": 730,
  "memory_peak_mb": 4820,
  "temperature_celsius": 42.3
}
```

## 8. MobileCore 扩展 API

OpenAI-compatible API 之外的能力放到：

```text
/mobilecore/*
```

### POST /mobilecore/model/load

按公开的模型标识加载已经安装并通过扫描的 GGUF。普通客户端不得提交或保存绝对路径。

```json
{
  "model_id": "qwen2.5-0.5b-instruct-q4_k_m",
  "context_length": 2048,
  "threads": 4
}
```

服务端只会解析应用允许的模型目录；不存在、越界或非 GGUF 文件统一返回 `artifact_missing`。旧版 `path` 字段只保留给受控宿主 QA，MobileCode 和 MobileCore UI 都只使用 `model_id`。

### POST /mobilecore/model/unload

卸载当前模型并释放推理资源，不删除模型文件。请求体为 `{}`；响应包含 `previous_model`、`model_loaded` 和 `backend`。

### POST /mobilecore/inference/cancel

MobileCode 在本地推理超时或用户暂停 Agent 时调用该认证回环端点。MobileCore 将幂等取消信号转发给原生 runtime，停止 token 解码；原请求返回类型化的 `cancelled` 失败。取消请求不携带 prompt、媒体、凭据或生成内容。

```json
{
  "ok": true,
  "cancel_requested": true
}
```

MobileCore 同一时间只允许一个主模型推理请求。并发 chat 或推理期间的模型加载、卸载会以 `runtime_busy` 拒绝，避免多个 NanoHTTPD 请求同时修改同一个 llama context。

### GET /v1/recommendations

返回适合当前设备的已安装模型、适配等级、内存估算、量化方式和预期性能。响应不包含模型文件路径；加载推荐模型时使用返回的 `model_id`。

### GET /mobilecore/device-profile

返回设备信息：

```json
{
  "device": {
    "brand": "HONOR",
    "model": "example",
    "android_version": "15",
    "ram_total_gb": 16,
    "ram_available_gb": 9.8,
    "soc": "unknown",
    "storage_available_gb": 128
  }
}
```

### POST /mobilecore/benchmark/run

启动 benchmark：

```json
{
  "model": "qwen3-4b-q4_k_m",
  "profile": "standard-short",
  "context_length": 2048,
  "max_tokens": 256
}
```

### GET /mobilecore/benchmark/latest

返回最近一次 benchmark：

```json
{
  "model": "qwen3-4b-q4_k_m",
  "device": "HONOR example",
  "results": {
    "load_time_ms": 6200,
    "first_token_ms": 730,
    "decode_tokens_per_second": 18.6,
    "memory_peak_mb": 4820,
    "stable": true
  },
  "recommendation": {
    "tier": "recommended",
    "max_suggested_model_size": "7B/8B Q4",
    "suggested_context_length": 4096
  }
}
```

## 9. 错误格式

统一错误格式：

```json
{
  "error": {
    "message": "Model is not loaded.",
    "type": "mobilecore_runtime_error",
    "code": "model_not_loaded"
  }
}
```

常见错误码：

```text
model_not_found
model_not_loaded
model_load_failed
projector_incompatible
projector_load_failed
out_of_memory
context_too_large
invalid_request
unauthorized
backend_crashed
service_busy
unsupported_modality
artifact_missing
checksum_mismatch
insufficient_memory
insufficient_storage
media_too_large
cancelled
```

Qwen2.5-Omni 双 artifact 的 `status/install/cancel/verify/load/uninstall` 契约见 `docs/mobilecode-local-multimodal-contract.md`；正式 OpenAPI 位于 `src/mobilecore-api/openai-compatible-api-v0.1.yaml`。

## 10. MobileCode 接入示例

MobileCode provider 配置：

```json
{
  "id": "mobilecore-local",
  "name": "MobileCore Local",
  "type": "openai-compatible",
  "baseUrl": "http://127.0.0.1:8080/v1",
  "apiKey": "local",
  "modelsEndpoint": "/models",
  "chatEndpoint": "/chat/completions",
  "defaultModel": "mobilecore-active",
  "stream": true
}
```

`mobilecore-active` 是动态路由占位符；MobileCode 每次请求前通过 `/health` 完成 v2 协议握手并解析真实的 `active_model`，再以 `/v1/models`、`/v1/recommendations` 和模型控制接口完成能力协商。MobileCode 不应该依赖 `/mobilecore/*` 才能完成基本聊天；扩展接口只用于状态、跑分、推荐与高级联动。
