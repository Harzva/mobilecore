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

默认只监听 localhost。

LAN 模式示例：

```text
http://<phone-ip>:8080/v1
```

LAN 模式必须由用户主动开启。

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
        "path": "/storage/emulated/0/MobileCore/models/qwen3-4b-q4_k_m.gguf",
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
  "version": "0.1.0",
  "model_loaded": true,
  "active_model": "qwen3-4b-q4_k_m",
  "backend": "llama.cpp"
}
```

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
  "requests_failed": 1,
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
out_of_memory
context_too_large
invalid_request
unauthorized
backend_crashed
service_busy
```

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
  "defaultModel": "qwen3-4b-q4_k_m",
  "stream": true
}
```

MobileCode 不应该依赖 `/mobilecore/*` 才能完成基本聊天。`/mobilecore/*` 只用于状态、跑分、推荐与高级联动。
