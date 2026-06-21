# Mobile AI Stack 总体设计

## 1. 背景

移动端 AI 应用正在从“聊天入口”走向“运行时平台”。如果只做一个聊天 App，价值会被模型厂商 API 和 UI 同质化快速稀释；如果做的是运行时层，则可以承接 Agent、模型、工具、文件、Git、MCP、本地推理等更大的生态。

Mobile AI Stack 的核心思想是：

```text
不要把移动端 AI 做成单个大而全 App，
而是拆成上层 Agent Harness 与下层 Model Runtime。
```

## 2. 两层产品矩阵

### 2.1 MobileCode：移动端 Agent Harness

MobileCode 负责“怎么使用模型完成工作”。

它的核心能力包括：

- 移动端项目管理；
- 文件浏览、编辑、预览；
- Git 仓库管理；
- Agent 对话与任务执行；
- 类 Claude Code / Codex 的移动端交互；
- MCP / Tool / CLI Runtime 接入；
- 云端模型 API 与本地模型 API 的统一调用。

MobileCode 是上层 IDE 与 Harness。

### 2.2 MobileCore（推嘛）：移动端模型运行时

MobileCore 负责“模型如何在手机本地运行”。

它的核心能力包括：

- 本地 LLM 推理；
- 模型下载、导入、管理；
- GGUF / MLC / ONNX 等模型格式支持；
- OpenAI-compatible API；
- 手机本地 LLM 跑分；
- 机型与模型适配推荐；
- 本地 API 安全与权限控制。

MobileCore 是下层 Runtime 与 API Server。

## 3. 体系结构

```text
┌─────────────────────────────────────┐
│             MobileCode               │
│  Mobile Agent IDE / Harness           │
│  - Workspace                          │
│  - Git                                │
│  - Files                              │
│  - Agent Chat                         │
│  - Tool Calling                       │
└───────────────────┬─────────────────┘
                    │ OpenAI-compatible API
                    ▼
┌─────────────────────────────────────┐
│          MobileCore（推嘛）           │
│  Local Model Runtime / API Server     │
│  - Model Manager                      │
│  - Inference Engine                   │
│  - Benchmark                          │
│  - Recommendation                     │
│  - Local API                          │
└───────────────────┬─────────────────┘
                    ▼
┌─────────────────────────────────────┐
│ llama.cpp / MLC LLM / ONNX / NPU      │
│ GGUF / MLC / ONNX / future formats    │
└─────────────────────────────────────┘
```

## 4. 为什么要拆成两个 App

如果把 MobileCode 和 MobileCore 放在一个 App 里，会出现几个问题：

1. **内存冲突**：Agent IDE 本身占用内存，本地模型也占用内存，两者合并更容易被 Android 杀后台。
2. **产品定位混乱**：一个是 Coding Agent，一个是 Local LLM Runtime，用户心智不同。
3. **生态扩展困难**：MobileCore 如果独立，可以给其他 App 调用；MobileCode 如果独立，也可以接 OpenAI、Claude、Gemini、DeepSeek 等云端 API。
4. **测试边界清晰**：MobileCore 跑分与模型适配可以独立验证；MobileCode 的 Harness 评测也可以独立验证。

因此推荐：

```text
MobileCode = Agent 层
MobileCore = 模型层
```

## 5. 与 OpenAI-compatible API 的关系

MobileCore 对外提供本地 OpenAI-compatible API，例如：

```text
http://127.0.0.1:8080/v1/chat/completions
http://127.0.0.1:8080/v1/models
```

MobileCode 只需要配置：

```text
Base URL: http://127.0.0.1:8080/v1
API Key: local
Model: qwen3-4b-q4_k_m
```

即可把本地手机模型当作 OpenAI-compatible provider 使用。

这可以最大程度减少 Adapter 维护成本。

## 6. 云端与本地混合策略

Mobile AI Stack 不应只押注本地模型，也不应只押注云端模型，而应支持混合策略：

```text
本地小模型：快速响应、离线、隐私、低成本
云端大模型：复杂推理、代码生成、长上下文、多模态
```

推荐设计：

- 简单问答、本地文档摘要、轻量规划：MobileCore 本地模型处理；
- 大规模代码修改、复杂 Agent 任务：MobileCode 调用云端 GPT/Claude/Gemini；
- 无网络场景：MobileCode 自动降级到 MobileCore；
- 隐私场景：用户可强制仅使用 MobileCore。

## 7. 品牌体系

```text
Mobile AI Stack
├── MobileCode：移动端 Agent IDE / Harness
└── MobileCore（推嘛）：移动端 Local LLM Runtime
```

推荐宣传语：

> MobileCode 负责让 AI 在手机上工作，MobileCore 负责让模型在手机上运行。

中文宣传语：

> 手机跑模型？推嘛。手机跑 Agent？MobileCode。
