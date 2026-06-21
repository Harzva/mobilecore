# MobileCore API Smoke Test v0.1

## 1. 目标

验证 MobileCore 本地 API 是否满足 MobileCode 的最小调用需求。

## 2. 测试项

```text
GET  /v1/models
POST /v1/chat/completions non-stream
POST /v1/chat/completions stream
GET  /health
GET  /metrics
```

## 3. MobileCode 最小兼容标准

```text
✓ /v1/models 返回模型列表
✓ /v1/chat/completions 返回 choices[0].message.content
✓ stream 模式返回 SSE chunk
✓ 服务不可用时返回清晰错误
✓ 默认 localhost，不暴露 LAN
```

## 4. 测试配置

```text
Base URL: http://127.0.0.1:8080/v1
API Key: local
Model: local-model
```
