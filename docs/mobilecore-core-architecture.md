# MobileCore 核心架构说明

## 1. 产品定义

MobileCore（推嘛 / TuiMa）是 Android 移动端本地模型运行时。它不以聊天为核心，而以“本地推理服务 + 模型管理 + API 输出 + 跑分推荐”为核心。

MobileCore 的最终目标不是替代 MobileCode，而是成为 MobileCode 和其他移动端 AI App 的本地模型基础设施。

## 2. 核心原则

### 2.1 Runtime first，不是 Chat first

MobileCore 可以内置一个简单聊天界面用于测试，但主产品心智不是聊天助手，而是：

```text
Local LLM Runtime for Android
```

这意味着优先级应是：

1. 模型是否能稳定加载；
2. 推理速度是否可接受；
3. API 是否兼容；
4. 后台服务是否稳定；
5. 其他 App 是否能调用；
6. 跑分是否可信；
7. 聊天界面是否好看。

聊天 UI 不是第一优先级。

### 2.2 API first，减少 Adapter 成本

MobileCore 对外必须尽量兼容 OpenAI API。MobileCode 不应该为 MobileCore 写一套特殊协议，而应该把 MobileCore 当作一个 OpenAI-compatible provider。

推荐最小 API：

```text
GET  /v1/models
POST /v1/chat/completions
POST /v1/completions       optional
POST /v1/embeddings        phase 2
GET  /health
GET  /metrics
```

### 2.3 Local first，安全默认

MobileCore 默认只监听：

```text
127.0.0.1
```

不默认开放局域网端口。若用户需要让电脑或其他设备访问，必须主动开启 LAN 模式，并显示清楚风险。

## 3. 模块划分

```text
MobileCore
├── App Shell
│   ├── 首页 / 状态面板
│   ├── 模型管理
│   ├── API 服务面板
│   ├── 跑分面板
│   └── 设置与权限
│
├── Model Manager
│   ├── 本地模型扫描
│   ├── 模型导入
│   ├── 模型元数据识别
│   ├── 模型完整性校验
│   └── 默认模型选择
│
├── Inference Runtime
│   ├── llama.cpp backend
│   ├── MLC LLM backend（后续）
│   ├── ONNX Runtime backend（后续）
│   └── NPU/NNAPI backend（后续探索）
│
├── Local API Server
│   ├── OpenAI-compatible routes
│   ├── Streaming response
│   ├── Request queue
│   ├── Session management
│   └── API auth / allowlist
│
├── Benchmark Engine
│   ├── Prefill benchmark
│   ├── Decode benchmark
│   ├── First token latency
│   ├── Memory peak
│   ├── Temperature sampling
│   └── Stability check
│
└── Recommendation Engine
    ├── Device profile
    ├── RAM / SoC / storage detection
    ├── Quantization suggestion
    ├── Context length suggestion
    └── Model tier recommendation
```

## 4. 第一阶段后端选择

第一阶段建议优先选择：

```text
llama.cpp + GGUF
```

原因：

- GGUF 是当前本地 LLM 生态中最成熟的轻量推理格式之一；
- llama.cpp 支持 Android / Linux / macOS / Windows；
- 有现成 server 模式可参考 OpenAI-compatible API；
- 量化模型资源丰富；
- 对 1B、3B、7B、8B 模型较友好。

## 5. Android 集成路线

### 路线 A：外部进程 server

```text
Android App
  ↓ start process
llama.cpp server binary
  ↓ localhost
OpenAI-compatible API
```

优点：

- 快速验证；
- 与桌面 llama.cpp server 架构接近；
- 方便复用已有 server 逻辑。

缺点：

- Android 上进程管理、权限、保活需要处理；
- 日志、崩溃恢复、后台限制较复杂。

### 路线 B：JNI / NDK 内嵌推理

```text
Android App
  ↓ JNI
llama.cpp native library
  ↓ in-process inference
Local API server / binder bridge
```

优点：

- 产品化更稳定；
- UI 与推理状态更好同步；
- 更容易控制生命周期。

缺点：

- 开发成本更高；
- 需要维护 native build；
- 崩溃会影响整个 App。

### 推荐

MVP 可先采用路线 A 快速打通，正式产品逐步迁移到路线 B。

## 6. MobileCode 调用方式

MobileCode 侧只需要把 MobileCore 当作 provider：

```json
{
  "provider": "mobilecore",
  "baseUrl": "http://127.0.0.1:8080/v1",
  "apiKey": "local",
  "model": "qwen3-4b-q4_k_m"
}
```

MobileCode 不应直接耦合 MobileCore 的内部模型管理、跑分逻辑、runtime 后端。

## 7. 后台服务与 Android 限制

MobileCore 要面对 Android 的后台限制：

- 应使用 foreground service 显示“本地模型服务运行中”；
- 用户应能一键停止模型服务释放内存；
- 模型加载期间应避免系统杀进程；
- 长时间推理时需要温度与耗电提示；
- 大模型运行时应建议关闭其他高内存 App。

## 8. 安全模型

默认策略：

```text
只监听 localhost
需要 API token
限制并发
限制最大上下文
限制最大输出 token
提供一键关闭服务
```

高级策略：

- App allowlist；
- LAN 模式确认；
- 访问日志；
- 请求来源提示；
- 敏感文件路径保护。

## 9. 核心判断

MobileCore 的价值不在于“又做一个聊天软件”，而在于把 Android 手机变成一个可被其他 App 调用的本地模型节点。

它更接近：

```text
Ollama for Android
+ LM Studio Mobile
+ Geekbench for Local LLM
```

而不是：

```text
ChatGPT clone
```
