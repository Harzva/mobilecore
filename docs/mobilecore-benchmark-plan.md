# MobileCore Benchmark 方案

## 1. 目标

MobileCore Benchmark 用于回答一个核心问题：

> 这台 Android 手机适合离线运行什么规模、什么量化等级、什么上下文长度的本地大模型？

它不是单纯跑 token/s，而是面向真实移动端使用场景，综合评估：

- 模型加载速度；
- 首 token 延迟；
- decode token/s；
- prefill 速度；
- 内存峰值；
- 发热趋势；
- 稳定性；
- 耗电趋势；
- 是否容易被系统杀后台；
- 是否适合 MobileCode 调用。

## 2. 跑分指标

### 2.1 基础指标

```text
load_time_ms
first_token_latency_ms
prefill_tokens_per_second
decode_tokens_per_second
total_tokens_per_second
memory_peak_mb
memory_available_before_mb
memory_available_after_mb
```

### 2.2 移动端指标

```text
temperature_start_celsius
temperature_peak_celsius
battery_start_percent
battery_end_percent
thermal_throttling_detected
background_killed
service_restart_required
```

### 2.3 稳定性指标

```text
success_runs
failed_runs
oom_count
backend_crash_count
average_latency_ms
p95_latency_ms
```

## 3. Benchmark Profile

### 3.1 quick profile

用于快速判断模型能否跑起来。

```text
prompt tokens: 128 左右
output tokens: 64
runs: 1
```

### 3.2 standard profile

用于常规排行榜。

```text
prompt tokens: 512 左右
output tokens: 256
runs: 3
```

### 3.3 coding profile

用于 MobileCode 场景。

```text
prompt: 小型代码理解/生成任务
output tokens: 512
runs: 3
```

### 3.4 long-context profile

用于测试上下文压力。

```text
prompt tokens: 2048 / 4096 / 8192
output tokens: 256
runs: 1
```

## 4. 推荐等级

MobileCore 不只给数字，还要给用户可理解的建议。

### 4.1 recommended

```text
可以稳定运行，速度可接受，不明显发热，不容易 OOM。
```

### 4.2 usable

```text
可以运行，但速度一般或发热明显，适合轻量任务。
```

### 4.3 experimental

```text
能勉强运行，但可能慢、热、容易被杀后台。
```

### 4.4 not recommended

```text
不建议运行，可能 OOM、崩溃或体验极差。
```

## 5. 机型推荐规则初版

仅作为初始规则，后续应由真实跑分数据修正。

```text
6GB RAM:
  recommended: 0.5B / 1B Q4
  usable: 1.5B Q4
  not recommended: 3B+

8GB RAM:
  recommended: 1B / 1.5B Q4
  usable: 3B Q4
  experimental: 7B Q3/Q4

12GB RAM:
  recommended: 3B / 4B Q4
  usable: 7B / 8B Q4
  experimental: 14B Q3/Q4

16GB RAM:
  recommended: 7B / 8B Q4
  usable: 12B / 14B Q4
  experimental: 14B Q5 或更长上下文

24GB RAM:
  recommended: 14B Q4/Q5
  usable: 30B/32B 低量化
  experimental: 更大模型低量化
```

注意：实际结果取决于 Android 系统占用、SoC、散热、推理后端和上下文长度。

## 6. 输出报告格式

### 6.1 JSON 报告

```json
{
  "schema_version": "mobilecore-benchmark-v0.1",
  "device": {
    "brand": "HONOR",
    "model": "example",
    "android_version": "15",
    "ram_total_gb": 16,
    "soc": "unknown"
  },
  "model": {
    "id": "qwen3-4b-q4_k_m",
    "format": "gguf",
    "size_bytes": 2600000000,
    "quantization": "Q4_K_M",
    "backend": "llama.cpp"
  },
  "profile": "standard",
  "results": {
    "load_time_ms": 6200,
    "first_token_latency_ms": 730,
    "prefill_tokens_per_second": 95.2,
    "decode_tokens_per_second": 18.6,
    "memory_peak_mb": 4820,
    "temperature_peak_celsius": 42.3,
    "stable": true
  },
  "recommendation": {
    "tier": "recommended",
    "summary": "适合日常离线问答与轻量 MobileCode 本地任务。",
    "suggested_context_length": 4096,
    "suggested_max_tokens": 512
  }
}
```

### 6.2 用户展示文案

```text
你的手机适合运行：7B/8B Q4 模型。
当前模型 qwen3-4b-q4_k_m 表现稳定，平均输出速度 18.6 tok/s，适合离线聊天、摘要和轻量代码辅助。
```

## 7. 与排行榜的关系

MobileCore 可以构建一个本地 LLM 手机跑分榜：

```text
设备型号 | RAM | SoC | 模型 | 量化 | token/s | 首token | 内存峰值 | 推荐等级
```

排行榜必须注意：

- 用户同意后才上传；
- 不上传个人文本 prompt；
- 只上传设备、模型、指标；
- 允许匿名；
- 明确模型版本和量化文件。

## 8. 与 MobileCode 的 Benchmark 联动

MobileCode 可以基于 MobileCore Benchmark 做 Harness 决策：

```text
如果 MobileCore 速度 > 15 tok/s 且模型推荐等级为 recommended：
  简单任务默认本地模型

如果 MobileCore 速度 < 8 tok/s：
  只用于短问答，不用于复杂 Agent

如果模型 unstable：
  MobileCode 不自动使用本地模型
```

## 9. P0 验证任务

第一批 benchmark 建议测试：

```text
模型：
- Qwen 0.5B Q4
- Qwen 1.5B Q4
- Qwen 3B Q4
- Qwen 7B/8B Q4
- Gemma 2B Q4
- Phi mini Q4

设备：
- 6GB RAM Android
- 8GB RAM Android
- 12GB RAM Android
- 16GB RAM Android
```

目标不是追求大模型，而是形成“手机适配模型”的可信数据。
