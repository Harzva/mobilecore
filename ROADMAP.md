# MobileCore Roadmap

## v0.1.0 - Local GGUF Inference Probe

目标：验证 Android 手机上可以稳定运行 GGUF 本地模型。

- [x] 初始化 Android 项目或技术验证目录。
- [x] 准备 llama.cpp Android 运行方式。
- [x] 支持导入/选择 GGUF 模型。
- [x] 支持一次 prompt 推理。
- [x] 显示加载时间、输出速度、错误日志。
- [x] 形成 P0 实测报告。

## v0.2.0 - OpenAI-compatible Local API

目标：让 MobileCode 和其他 App 能调用 MobileCore。

- [x] 实现 `/v1/models`。
- [x] 实现 `/v1/chat/completions`。
- [ ] 支持 SSE streaming。
- [x] 支持基础本地 Bearer token。
- [x] 默认监听 `127.0.0.1`。
- [x] 写入 MobileCode provider preset。

## v0.3.0 - Benchmark Engine

目标：建立移动端本地 LLM 跑分能力。

- [ ] quick / standard / coding / long-context profiles。
- [x] 记录 load time、first token latency、decode tok/s。
- [x] 记录内存峰值。
- [ ] 记录温度、电量趋势。
- [x] 输出 JSON metrics / recommendation response。
- [x] 给出 perfect / usable / risky 等级。

## v0.4.0 - Model Manager

目标：让普通用户无需命令行管理模型。

- [x] 模型列表。
- [x] 模型导入。
- [x] 模型元数据识别。
- [x] 量化等级识别。
- [x] 文件大小与存储占用展示。
- [ ] 默认模型切换。
- [ ] 删除模型与安全确认。

## v0.5.0 - MobileCode Integration

目标：让 MobileCode 与 MobileCore 形成 Mobile AI Stack 闭环。

- [ ] MobileCode provider preset。
- [ ] MobileCore 服务状态检测。
- [ ] 一键复制 API 配置。
- [ ] 本地/云端模型切换策略。
- [ ] 离线模式降级策略。
- [ ] MobileCode 中显示 MobileCore benchmark 结果。

## v1.0.0 - Public Stable Release

目标：形成可公开发布的 Android Local LLM Runtime。

- [ ] 稳定 API。
- [ ] 完整文档。
- [ ] 多机型实测。
- [ ] 多模型实测。
- [ ] 安全默认配置。
- [ ] APK 发布包。
- [ ] Mobile AI Stack 总体说明。

## 长期方向

- [ ] MLC LLM backend。
- [ ] ONNX Runtime backend。
- [ ] ExecuTorch backend。
- [ ] Android NPU / NNAPI 探索。
- [ ] 本地 embedding API。
- [ ] 本地 RAG 小服务。
- [ ] 手机 LLM 跑分排行榜。
- [ ] 第三方 App SDK。
