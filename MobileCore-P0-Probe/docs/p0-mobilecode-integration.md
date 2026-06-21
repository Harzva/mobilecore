# P0 MobileCode Integration

## 1. 目标

让 MobileCode 在 P0 阶段把 MobileCore 当作一个 OpenAI-compatible provider 调用。

MobileCode 不需要知道底层是 Termux、llama.cpp 还是 GGUF，只需要知道：

```text
baseUrl = http://127.0.0.1:8080/v1
model = local-model
apiKey = local
```

## 2. 调用结构

```text
MobileCode
  ↓ HTTP OpenAI-compatible API
MobileCore P0 / llama-server
  ↓
GGUF local model
```

## 3. Provider 配置建议

```json
{
  "id": "mobilecore-p0-local",
  "name": "MobileCore P0 Local",
  "type": "openai-compatible",
  "baseUrl": "http://127.0.0.1:8080/v1",
  "apiKey": "local",
  "defaultModel": "local-model",
  "supportsStreaming": true,
  "supportsTools": false,
  "supportsEmbeddings": false,
  "notes": "P0 uses llama.cpp/llama-server in Termux."
}
```

## 4. 基础请求

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer local" \
  -d '{
    "model":"local-model",
    "messages":[{"role":"user","content":"用一句话介绍 MobileCore。"}],
    "max_tokens":128,
    "stream":false
  }'
```

## 5. MobileCode 侧 UI 建议

MobileCode 中可以新增一个 provider 卡片：

```text
MobileCore Local
状态：未连接 / 已连接 / 模型运行中 / 错误
Base URL: http://127.0.0.1:8080/v1
模型：local-model
```

按钮：

```text
测试连接
测试生成
复制配置
查看 MobileCore 文档
```

## 6. 连接检测

MobileCode 可先访问：

```text
GET http://127.0.0.1:8080/v1/models
```

如果失败，再访问：

```text
GET http://127.0.0.1:8080/health
```

P0 阶段 llama-server 不一定支持 `/health`，因此 `/v1/models` 是主要检测入口。

## 7. 常见问题

### 7.1 MobileCode 调不到 localhost

可能原因：

- MobileCode WebView 网络策略限制；
- Android cleartext traffic 未允许；
- llama-server 没有启动；
- 端口不是 8080；
- MobileCode 与 Termux 网络命名空间表现不一致。

处理：

- 优先用浏览器访问 `http://127.0.0.1:8080/v1/models`；
- 再用 MobileCode 测试；
- 如 127.0.0.1 不可用，测试 `localhost`；
- 必要时开放 `0.0.0.0` 仅作开发测试，但正式版禁止默认这样做。

### 7.2 模型太慢

P0 阶段慢不是失败，只要链路跑通即可。

MobileCode 应显示：

```text
本地模型速度较慢，建议用于短任务或切换云端模型。
```

### 7.3 工具调用不支持

P0 本地模型不强求 tool calling。

MobileCode 应在本地 provider 中禁用复杂工具调用，或由 MobileCode 自己管理工具执行，模型只负责文本决策。

## 8. P0 验收

MobileCode 集成成功的标准：

```text
✓ MobileCode provider 能保存 MobileCore 配置
✓ 测试连接成功
✓ 能发送 chat completion 请求
✓ 能显示非流式输出
✓ 最好支持流式输出
✓ 错误时有明确提示
```
