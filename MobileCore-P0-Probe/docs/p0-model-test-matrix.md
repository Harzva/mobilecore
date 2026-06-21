# P0 Model Test Matrix

## 1. 目标

模型测试矩阵用于判断不同 RAM Android 手机适合运行的本地模型规模。

P0 不追求大而全，优先测试能代表不同档位的模型：

```text
0.5B / 1B / 1.5B / 3B / 7B / 8B / 14B
```

## 2. 模型格式

P0 优先使用：

```text
GGUF + Q4_K_M / Q4_0 / Q5_K_M
```

原因：

- llama.cpp 支持成熟；
- 手机端可运行；
- 文件体积适中；
- 社区模型资源丰富。

## 3. 推荐测试模型

| 档位 | 模型类型 | 推荐量化 | 目标设备 | P0 优先级 |
|---|---|---|---|---|
| Tiny | Qwen/Gemma/Phi 0.5B-1B | Q4 | 6GB+ | 必测 |
| Small | Qwen 1.5B | Q4 | 6GB/8GB+ | 必测 |
| Medium | Qwen 3B / Gemma 2B | Q4 | 8GB/12GB+ | 必测 |
| Mainstream | Qwen/Llama/Gemma 7B-8B | Q4 | 12GB/16GB+ | 必测 |
| Large-Mobile | 12B-14B | Q4/Q3 | 16GB/24GB+ | 可选 |
| Beyond-Mobile | 30B+ | Q2/Q3 | 24GB+ | P0 不建议 |

## 4. RAM 推荐规则初版

| 手机 RAM | 推荐模型 | 可用模型 | 实验模型 |
|---|---|---|---|
| 6GB | 0.5B/1B Q4 | 1.5B Q4 | 3B Q4 |
| 8GB | 1.5B Q4 | 3B Q4 | 7B Q3/Q4 |
| 12GB | 3B/4B Q4 | 7B/8B Q4 | 14B Q3 |
| 16GB | 7B/8B Q4 | 12B/14B Q4 | 14B Q5 |
| 24GB | 14B Q4/Q5 | 30B/32B 低量化 | 更大模型低量化 |

## 5. 测试参数

P0 默认参数：

```text
context_length: 2048 或 4096
max_tokens: 128 / 256
batch: 默认
threads: 自动或手动记录
temperature: 0.7
```

如果 OOM：

```text
先降低 context_length
再换更小模型
再换更低量化
```

## 6. 测试记录模板

```text
Device:
Android:
RAM total:
RAM available before load:
Model:
Model size:
Quantization:
Backend:
Context length:
Load success: yes/no
Load time:
First token latency:
Decode tok/s:
Peak memory:
Temperature start:
Temperature peak:
Battery start/end:
Stable for 3 runs: yes/no
Recommendation: recommended/usable/experimental/not recommended
Notes:
```

## 7. P0 推荐结论格式

示例：

```text
HONOR 16GB 设备：
- Qwen 3B Q4：recommended，适合日常离线问答与轻量代码。
- Qwen 7B Q4：recommended/usable，适合更高质量问答，但发热更明显。
- 14B Q4：experimental，可加载但速度慢、内存压力大。
```

## 8. MobileCode 使用建议

MobileCode 不应默认使用过大的本地模型。

推荐策略：

```text
recommended: 可用于默认本地模型
usable: 用户确认后使用
experimental: 只用于手动测试
not recommended: 不在 MobileCode 中自动选择
```
