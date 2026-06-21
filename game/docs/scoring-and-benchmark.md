# TuiMa Push Scoring & Benchmark

## 1. 设计原则

TuiMa Push 的得分不能只看模型大小，否则低速勉强跑大模型也会刷高分。

得分应综合：

```text
模型规模
推理速度
首 token 延迟
内存效率
温度表现
稳定性
通关进度
步数效率
```

最终分数要能解释用户体验：

```text
这台设备不仅能跑多大模型，还要跑得是否足够快、足够稳、足够省资源。
```

## 2. Benchmark 触发时机

当模型箱子被推到目标手机格后触发 benchmark。

流程：

```text
1. 读取模型档位
2. MobileCore 选择实际模型文件或推荐模型
3. 加载模型
4. 运行标准 prompt
5. 记录性能指标
6. 判断是否通过
7. 计算该模型分数
8. 更新棋盘状态
```

## 3. Benchmark 指标

### 基础指标

```text
model_size_tier
model_file_size_gb
quantization
context_length
load_time_ms
first_token_ms
prefill_tokens_per_second
decode_tokens_per_second
completion_tokens
memory_peak_mb
memory_available_before_mb
memory_available_after_mb
```

### 移动端指标

```text
temperature_start_celsius
temperature_peak_celsius
battery_start_percent
battery_end_percent
thermal_throttling_detected
background_killed
```

### 稳定性指标

```text
runs_total
runs_success
runs_failed
oom_count
runtime_crash_count
cancelled
```

## 4. 模型基础分

建议初版：

```text
0.5B  = 500
1.5B  = 1000
3B    = 2000
7B/8B = 4000
14B   = 8000
32B+  = 16000
```

注意：基础分只是底座，最终还要乘以性能系数。

## 5. 单模型得分公式

推荐初版：

```text
model_score =
  base_score
  + speed_score
  + latency_bonus
  + memory_efficiency_bonus
  + stability_bonus
  - thermal_penalty
  - move_penalty
```

### speed_score

```text
speed_score = decode_tok_s × speed_weight
```

初版：

```text
speed_weight = 80
```

### latency_bonus

```text
latency_bonus = max(0, 1000 - first_token_ms) × 0.5
```

### memory_efficiency_bonus

```text
memory_efficiency = 1 - (memory_peak_mb / memory_budget_mb)
memory_efficiency_bonus = max(0, memory_efficiency) × 1000
```

### stability_bonus

```text
stability_bonus = successful_runs × 300
```

### thermal_penalty

```text
if temperature_peak_celsius <= 42:
  thermal_penalty = 0
elif temperature_peak_celsius <= 48:
  thermal_penalty = (temperature_peak_celsius - 42) × 80
else:
  thermal_penalty = 800 + (temperature_peak_celsius - 48) × 150
```

### move_penalty

```text
move_penalty = max(0, moves_used - par_moves) × 10
```

## 6. 总分公式

```text
total_score =
  sum(model_score for cleared_models)
  + stage_completion_bonus
  + board_bonus
  + upload_bonus
```

### stage_completion_bonus

```text
cleared 1 model: +100
cleared 2 models: +300
cleared 3 models: +700
cleared 4 models: +1500
cleared 5 models: +3000
```

### board_bonus

自定义棋盘根据难度增加少量奖励：

```text
board_bonus = difficulty_rating × 100
```

但排行榜默认按 board_id 分开比较，避免难度不一致造成刷分。

### upload_bonus

上传成功后可给轻量荣誉分：

```text
upload_bonus = 100
```

不应太高，避免为了上传刷分。

## 7. 设备等级

根据 best_cleared_model 和综合性能划分：

```text
Entry:
  best_model <= 1.5B

Standard:
  best_model = 3B

High-End:
  best_model = 7B/8B

Flagship:
  best_model = 14B

Extreme:
  best_model >= 32B
```

## 8. 模型通过阈值

初始阈值可设置为：

```text
0.5B:
  decode_tok_s >= 8
  first_token_ms <= 3000

1.5B:
  decode_tok_s >= 6
  first_token_ms <= 4000

3B:
  decode_tok_s >= 4
  first_token_ms <= 6000

7B/8B:
  decode_tok_s >= 2
  first_token_ms <= 10000

14B:
  decode_tok_s >= 1
  first_token_ms <= 15000
```

这些阈值要后续根据真实设备数据修正。

## 9. 标准 Prompt

MVP 先用固定轻量 prompt：

```text
Summarize the purpose of on-device LLM inference in three short bullet points.
```

中文测试可选：

```text
用三点简要说明手机本地运行大模型的价值。
```

为了公平，排行榜应固定：

```text
prompt
context_length
max_tokens
temperature
backend
quantization
```

## 10. 防作弊策略

MVP 阶段先做轻量校验：

```text
1. 限制提交字段范围
2. 分数由前端公式计算，但后端重新校验基本合法性
3. 提交时记录 board_id 和 model_tier
4. 同一 anonymous_id + device_class + board_id 做频率限制
5. 极端异常值进入 pending_review
```

正式阶段可加入：

```text
MobileCore result signature
runtime nonce
benchmark transcript hash
model file hash
```

## 11. 排行榜排序

默认排序：

```text
total_score DESC
best_model_tier DESC
avg_decode_tok_s DESC
created_at ASC
```

同设备分类榜：

```text
device_class + board_id
```

自定义棋盘榜：

```text
custom_board_id
```

## 12. 结果展示

用户完成后展示：

```text
Challenge Complete
Total Score: 12,560
Avg. Speed: 28.4 tok/s
Best Cleared Model: 14B
Stages Completed: 5/5
Device Tier: Flagship
```

并提示：

```text
Upload Result
Your result will be shared to the leaderboard.
Device class, score, average speed, and cleared models only.
No personal data.
```