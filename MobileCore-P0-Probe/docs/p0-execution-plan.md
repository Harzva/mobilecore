# P0 Execution Plan

## 1. 目标

P0 的目标是用最短路径验证：Android 手机是否可以作为本地 LLM API Server，为 MobileCode 提供离线模型能力。

核心链路：

```text
Android 手机
  ↓
Termux
  ↓
llama.cpp / llama-server
  ↓
GGUF 模型
  ↓
OpenAI-compatible API
  ↓
MobileCode
```

## 2. 为什么 P0 先用 Termux

MobileCore 最终可以做成漂亮 Android App，但 P0 不应该先陷入 UI、权限、NDK、JNI、后台服务等复杂工程。

Termux P0 的价值是：

1. 快速验证本地模型可跑。
2. 快速验证 llama.cpp server 可用。
3. 快速验证 OpenAI-compatible API 可用。
4. 快速验证 MobileCode 调用路径。
5. 获得真实手机 benchmark 数据。

如果 Termux P0 都跑不通，直接做完整 App 风险更高。

## 3. P0 阶段划分

### P0-A：环境检查

目标：确认手机是否具备基础环境。

检查项：

```bash
uname -a
getprop ro.product.model
getprop ro.product.brand
getprop ro.build.version.release
free -h
df -h
```

需要确认：

- Android 版本；
- RAM；
- 可用存储；
- CPU 架构；
- Termux 可用性；
- 网络是否可下载模型和源码。

### P0-B：安装推理后端

优先尝试：

```bash
pkg install llama-cpp
```

如果包不可用，再编译 llama.cpp：

```bash
pkg install git cmake clang make
 git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp
cmake -B build
cmake --build build -j4
```

### P0-C：准备 GGUF 模型

模型放置建议：

```text
~/mobilecore/models/
```

推荐先测试小模型：

```text
Qwen 0.5B Q4
Qwen 1.5B Q4
Qwen 3B Q4
```

16GB RAM 设备再测试：

```text
7B / 8B Q4
12B / 14B Q4 experimental
```

### P0-D：启动 llama-server

示例：

```bash
llama-server \
  -m ~/mobilecore/models/model.gguf \
  --host 127.0.0.1 \
  --port 8080 \
  -c 4096
```

如果是源码编译版本：

```bash
~/llama.cpp/build/bin/llama-server \
  -m ~/mobilecore/models/model.gguf \
  --host 127.0.0.1 \
  --port 8080 \
  -c 4096
```

### P0-E：测试 API

测试 models：

```bash
curl http://127.0.0.1:8080/v1/models
```

测试 chat completions：

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model":"local-model",
    "messages":[{"role":"user","content":"你好，用一句话介绍你自己。"}],
    "max_tokens":128,
    "temperature":0.7
  }'
```

### P0-F：MobileCode 接入

MobileCode provider 配置：

```json
{
  "provider": "mobilecore-p0",
  "type": "openai-compatible",
  "baseUrl": "http://127.0.0.1:8080/v1",
  "apiKey": "local",
  "model": "local-model"
}
```

### P0-G：记录跑分

记录：

```text
设备型号：
Android 版本：
RAM：
模型：
模型大小：
量化等级：
context：
load time：
first token latency：
decode tok/s：
内存峰值：
温度变化：
是否稳定：
```

## 4. 成功标准

P0 成功必须满足：

```text
✓ 至少一个 GGUF 模型能加载
✓ llama-server 能稳定启动
✓ /v1/models 可访问
✓ /v1/chat/completions 可访问
✓ MobileCode 可调用
✓ 有一份 benchmark 结果
```

## 5. 失败判定与处理

### 5.1 llama-cpp 包不可用

处理：源码编译 llama.cpp。

### 5.2 编译失败

处理：记录 clang/cmake 错误；换用预编译二进制或 Android NDK 路线。

### 5.3 模型 OOM

处理：降低模型规模、降低量化等级、降低 context。

### 5.4 API 可访问但 MobileCode 调不到

处理：检查 Android localhost 隔离、MobileCode WebView 网络限制、cleartext traffic 策略。

### 5.5 速度太慢

处理：保留结果；P0 重点是打通链路，不以速度作为唯一成败标准。

## 6. P0 完成后的决策

如果 P0 成功：

```text
进入 MobileCore v0.1.0 Android App Shell
```

如果 P0 失败但原因是 Termux 工程问题：

```text
改走 Android NDK / JNI llama.cpp 路线
```

如果 P0 失败且设备性能不足：

```text
降低推荐模型规模，保留设备为低端样本
```
