# MobileCore-P0-Probe

MobileCore-P0-Probe 是 MobileCore（推嘛）的第一阶段技术验证包，目标不是做完整 App，而是先验证 Android 手机本地模型运行链路是否成立。

## P0 验证目标

```text
Android / Termux
  ↓
llama.cpp / llama-server
  ↓
GGUF 本地模型
  ↓
OpenAI-compatible localhost API
  ↓
MobileCode 调用
```

P0 只验证 5 件事：

1. Android 手机上能否运行 llama.cpp / llama-server。
2. 能否加载 1B / 3B / 7B/8B GGUF 量化模型。
3. 能否在手机本地提供 `http://127.0.0.1:8080/v1` API。
4. 能否通过 OpenAI-compatible `/v1/chat/completions` 完成推理。
5. MobileCode 能否把 MobileCore 当作本地 provider 调用。

## P0 不做什么

P0 暂时不做：

- 完整 Android UI；
- 模型商店；
- 用户登录；
- 云同步；
- NPU 加速；
- 多模态；
- 完整跑分榜上传。

## 推荐先走 Termux 技术验证

P0 的最快路线是 Termux：

```text
Termux
  ↓
pkg install llama-cpp 或源码编译 llama.cpp
  ↓
运行 llama-server
  ↓
本地 curl 测试 API
  ↓
MobileCode 配置 baseUrl
```

这条路线能最快验证 MobileCore 的核心价值：

> 手机本地模型是否能以 OpenAI-compatible API 形式服务给 MobileCode。

## 文件说明

```text
MobileCore-P0-Probe/
├── README.md
├── docs/
│   ├── p0-execution-plan.md
│   ├── p0-model-test-matrix.md
│   ├── p0-mobilecode-integration.md
│   └── p0-risk-checklist.md
└── scripts/
    ├── termux_p0_setup.sh
    ├── start_llama_server.sh
    ├── test_openai_compatible_api.sh
    └── benchmark_quick.sh
```

## 最小验收标准

P0 成功的最低标准：

```text
✓ 手机端能启动 llama-server
✓ 能加载至少一个 GGUF 模型
✓ curl /v1/models 正常
✓ curl /v1/chat/completions 正常
✓ MobileCode 能通过 localhost 调用
✓ 记录 token/s、首 token 延迟、内存占用
```

## P0 推荐模型

初始建议：

```text
6GB RAM：0.5B / 1B Q4
8GB RAM：1.5B / 3B Q4
12GB RAM：3B / 7B Q4
16GB RAM：7B / 8B Q4，12B/14B Q4 作为 experimental
```

## 下一步

P0 验证成功后，进入：

```text
v0.1.0 Android App Shell
v0.2.0 OpenAI-compatible API 固化
v0.3.0 Benchmark Engine
```
