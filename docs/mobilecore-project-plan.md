# MobileCore（推嘛）项目计划书

## 1. 项目名称

- 英文名：MobileCore
- 中文名：推嘛 / TuiMa
- 所属体系：Mobile AI Stack
- 协同产品：MobileCode

## 2. 项目定位

MobileCore 是一个 Android 本地大模型运行时软件。它用于在手机本地运行离线大模型，并向 MobileCode 或其他第三方 App 提供 OpenAI-compatible API。

MobileCore 不是“另一个聊天机器人”，而是移动端 AI 基础设施。

## 3. 目标用户

### 3.1 移动端 AI Agent 用户

希望在手机上运行 MobileCode、OpenCode、Aider、轻量 Agent 或离线助手的用户。

### 3.2 本地模型爱好者

希望测试不同手机运行 Qwen、Llama、Gemma、Phi 等模型性能的用户。

### 3.3 开发者

希望在 Android 设备上获得本地 OpenAI-compatible API，用于自己的 App、脚本或工作流。

### 3.4 隐私敏感用户

希望在无网络或隐私场景下使用本地模型处理文本、笔记、代码、资料的用户。

## 4. 核心问题

当前 Android 本地 LLM 生态存在几个问题：

1. 本地模型 App 多以聊天为中心，缺少可复用 API 服务层。
2. Termux / llama.cpp 路线可行，但对普通用户不友好。
3. 不同手机适合跑多大模型缺少统一跑分与推荐。
4. 上层 Agent App 想调用本地模型，需要重复适配各种推理引擎。
5. 本地模型、云端模型、Agent Harness 之间没有清晰分层。

MobileCore 的目标是解决这些问题。

## 5. 产品目标

### 5.1 短期目标

完成 Android 端本地 GGUF 模型加载、推理、API 服务与基础跑分。

### 5.2 中期目标

形成稳定的本地模型服务层，让 MobileCode 可以调用 MobileCore 完成离线推理。

### 5.3 长期目标

成为 Android 本地 LLM 运行时标准层之一，为多个 App 提供统一 API、跑分榜和模型推荐能力。

## 6. MVP 范围

MVP 必须包含：

1. 模型导入：从本地文件选择 GGUF 模型。
2. 模型加载：加载模型并显示状态。
3. 本地推理：支持单轮/多轮 chat completion。
4. API 服务：提供 `/v1/models` 与 `/v1/chat/completions`。
5. Streaming：支持 SSE 流式输出。
6. 跑分：测试 token/s、首 token延迟、加载时间、内存峰值。
7. 推荐：根据 RAM 与跑分结果给出模型适配建议。
8. MobileCode 对接：提供 base URL、model、api key 配置说明。

MVP 暂不强求：

- 多模态；
- 训练/微调；
- NPU 专属加速；
- 完整模型商店；
- 复杂 RAG；
- 全局后台永久保活。

## 7. 版本规划

### v0.1.0：本地推理打通

目标：验证手机本地 GGUF 模型可运行。

任务：

- Android 项目初始化；
- 集成 llama.cpp server 或 native binary；
- 支持选择 GGUF 模型；
- 支持输入 prompt 并返回输出；
- 显示模型加载状态与错误日志。

验收：

- 能在 8GB/12GB/16GB Android 设备上运行 1B/3B/7B Q4 模型；
- 能完成一次本地离线问答；
- 崩溃时有明确错误提示。

### v0.2.0：OpenAI-compatible API

目标：让其他 App 能调用 MobileCore。

任务：

- 实现 `/v1/models`；
- 实现 `/v1/chat/completions`；
- 支持 stream 与非 stream；
- 支持 localhost 端口配置；
- 支持 API key 或 local token；
- 输出 MobileCode 接入文档。

验收：

- MobileCode 能通过 `http://127.0.0.1:<port>/v1` 调用本地模型；
- OpenAI SDK 兼容性通过基础测试；
- stream 输出可正常显示。

### v0.3.0：跑分与机型适配

目标：建立 MobileCore Benchmark。

任务：

- 设计统一 benchmark prompt；
- 测试加载耗时；
- 测试 prefill speed；
- 测试 decode speed；
- 测试 first token latency；
- 记录内存峰值、温度、耗电趋势；
- 输出模型推荐等级。

验收：

- 每台设备可以生成 benchmark report；
- 给出推荐模型规模：1B/3B/7B/8B/14B；
- 给出推荐量化等级：Q4/Q5/Q8；
- 支持导出 JSON 报告。

### v0.4.0：模型管理器

目标：让普通用户管理模型。

任务：

- 模型列表；
- 模型元信息识别；
- 文件大小、量化等级、上下文长度展示；
- 模型删除、重命名、默认模型设置；
- 模型完整性校验；
- 推荐模型入口。

验收：

- 用户无需命令行即可导入与切换模型；
- 模型信息展示清晰；
- 删除模型前有确认与空间提示。

### v0.5.0：Mobile AI Stack 联动

目标：与 MobileCode 形成完整体验。

任务：

- MobileCode provider preset；
- MobileCore 服务发现；
- 一键复制 API 配置；
- MobileCode 中显示 MobileCore 连接状态；
- 本地/云端模型切换策略；
- 离线模式策略。

验收：

- MobileCode 可以一键选择 MobileCore；
- 无网络时自动提示使用 MobileCore；
- 复杂任务可切云端，简单任务可走本地。

### v1.0.0：稳定公开版

目标：形成可公开发布的 Android Local LLM Runtime。

任务：

- 稳定 API；
- 完整文档；
- 安全策略；
- 跑分榜导出；
- 崩溃日志与诊断；
- 常见设备适配表；
- 发布 APK。

验收：

- 至少 5 类手机完成实测；
- 至少 10 个主流 GGUF 模型完成测试；
- MobileCode 联动可用；
- 文档可指导第三方 App 接入。

## 8. 技术路线

### 8.1 推理后端

优先级：

1. llama.cpp / GGUF；
2. MLC LLM；
3. ONNX Runtime；
4. ExecuTorch；
5. Android NNAPI / NPU 加速探索。

### 8.2 API 标准

优先兼容 OpenAI API：

- Chat Completions；
- Models；
- Embeddings 后续支持；
- SSE Streaming。

### 8.3 Android UI

可选技术栈：

- Kotlin + Jetpack Compose；
- React Native；
- Flutter。

如果目标是高性能 native runtime，推荐 Kotlin + Jetpack Compose；如果希望与 MobileCode 复用前端经验，可考虑 React Native / WebView Hybrid。

## 9. 风险与对策

### 风险 1：手机内存不足

对策：

- 默认推荐小模型；
- 自动限制上下文长度；
- 加载前估算内存；
- 低内存时阻止加载大模型。

### 风险 2：Android 后台杀进程

对策：

- 前台服务；
- 服务运行通知；
- 一键重启；
- 推理中保持唤醒策略。

### 风险 3：发热与耗电

对策：

- 温度提示；
- 长推理限速；
- benchmark 单独提示；
- 低电量保护。

### 风险 4：API 被局域网滥用

对策：

- 默认 localhost；
- LAN 模式显式开启；
- API token；
- 请求日志。

### 风险 5：模型来源复杂

对策：

- 只推荐可信模型来源；
- 文件 hash 校验；
- 模型卡片记录；
- 明确许可证信息。

## 10. 与 chagpt2localagent 的关系

当前文档与项目骨架通过 chagpt2localagent 写入本地目录。后续可以继续使用 chagpt2localagent / Codex Bridge 对 MobileCore 执行：

- 创建 Android 项目；
- 接入 llama.cpp；
- 编写 API server；
- 生成 benchmark 脚本；
- 生成 MobileCode provider preset；
- 自动检查文档与实现一致性。

MobileCore 本身不依赖 chagpt2localagent；chagpt2localagent 是开发阶段的本地自动化执行通道。

## 11. 下一步建议

下一步不要直接写完整 App，而是先做技术验证包：

```text
MobileCore-P0-Probe
```

验证内容：

1. Android 设备能否运行 llama.cpp binary；
2. 能否加载 1B/3B/7B GGUF；
3. 能否启动 localhost API；
4. MobileCode 能否调用；
5. benchmark 数据是否稳定。

P0 成功后再进入正式 App UI。