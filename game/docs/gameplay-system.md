# TuiMa Push Gameplay System

## 1. 核心玩法

TuiMa Push 的核心玩法是推箱子。

但普通推箱子里的箱子，在这里被替换成不同规模的大模型：

```text
0.5B box
1.5B box
3B box
7B box
14B box
32B+ box
```

每个模型箱子推到对应的目标手机格后，系统会触发对应模型档位的 benchmark。

如果模型能成功运行，该格子点亮，并给玩家加分。

## 2. 游戏语义映射

| 游戏元素 | 实际含义 |
|---|---|
| 角色 / Mascot | TuiMa 推嘛小助手 |
| 模型箱子 | 本地 LLM 模型规模 |
| 目标手机格 | 当前手机设备可运行验证点 |
| 推到目标格 | 模型跑通 |
| 星星奖励 | 分数加成 |
| 速度加成格 | 推理速度奖励 |
| 墙体/障碍 | 内存、温度、存储限制 |
| 上传徽章 | 提交结果到排行榜 |
| 通关星级 | 模型规模 + 性能综合评级 |

## 3. 模型箱子等级

### 0.5B

```text
颜色：绿色
难度：入门
意义：低端设备也应能尝试
```

### 1.5B

```text
颜色：浅蓝
难度：轻量
意义：基础离线 AI 能力
```

### 3B

```text
颜色：蓝色
难度：中等
意义：常见移动端小模型
```

### 7B / 8B

```text
颜色：紫蓝
难度：主流挑战
意义：高端手机本地推理能力分水岭
```

### 14B

```text
颜色：紫色
难度：高阶
意义：高内存设备挑战
```

### 32B+

```text
颜色：深紫 / 特殊发光
难度：极限
意义：实验性，仅特殊设备或未来设备挑战
```

## 4. 标准挑战关卡

### Device Challenge 01：Starter

```text
模型：0.5B, 1.5B
目标：确认设备能运行轻量模型
```

### Device Challenge 02：Daily Model

```text
模型：1.5B, 3B
目标：确认设备能运行日常小模型
```

### Device Challenge 03：Mobile Mainstream

```text
模型：3B, 7B
目标：确认设备是否达到主流本地 LLM 水平
```

### Device Challenge 04：High-End

```text
模型：0.5B, 1.5B, 3B, 7B, 14B
目标：高端设备完整挑战
```

### Device Challenge 05：Extreme

```text
模型：7B, 14B, 32B+
目标：极限挑战，不作为普通用户默认入口
```

## 5. 操作规则

基础规则：

```text
1. 玩家每次移动一个格子
2. 只能推动一个相邻模型箱子
3. 箱子不能穿墙
4. 箱子被推到目标手机格后触发 benchmark
5. benchmark 成功后该格子锁定为已通过
6. benchmark 失败后箱子退回或标记失败
```

可选规则：

```text
允许撤销 Undo
允许提示 Hint
允许重置 Reset
允许跳过某个模型档位，但总分下降
```

## 6. 目标格类型

### Phone Target

普通手机目标格。模型推入后运行 benchmark。

### Tablet Target

平板目标格。用于平板设备排行榜。

### Custom Device Target

自定义设备目标格。适合自定义棋盘和未来设备。

## 7. 障碍类型

### Wall

普通墙体。

### Memory Limit Block

表示内存限制。

### Thermal Block

表示温度限制。

### Storage Block

表示存储空间限制。

### Battery Block

表示低电量限制。

## 8. 奖励格

### Speed Boost

如果模型箱子经过该格，速度分加成。

### Memory Saver

如果 benchmark 内存峰值较低，获得额外奖励。

### Stability Star

连续多次成功运行，获得稳定性奖励。

### Upload Badge

鼓励上传结果，上传成功后获得榜单徽章。

## 9. 关卡成功条件

单个模型成功条件：

```text
model_load_success = true
completion_success = true
decode_tok_s >= threshold
first_token_ms <= threshold
memory_peak <= budget
no_crash = true
no_oom = true
```

整关成功条件：

```text
cleared_models_count >= required_count
score >= minimum_score
benchmark_result_valid = true
```

## 10. 失败状态

### OOM Failed

模型加载或推理时内存不足。

### Too Slow

模型能运行，但速度低于最低阈值。

### Thermal Risk

推理导致温度过高。

### Runtime Error

MobileCore API 调用失败。

### User Cancelled

用户手动取消 benchmark。

## 11. 游戏反馈

成功反馈：

```text
模型箱子变亮
目标手机格出现绿色勾
弹出速度和分数
排行榜上传入口出现
```

失败反馈：

```text
模型箱子轻微抖动
目标格显示失败原因
给出推荐：降低模型规模 / 降低上下文 / 换 Q4 量化
```

## 12. 游戏节奏

推荐每个关卡控制在：

```text
1-3 分钟完成推箱子
30 秒 - 3 分钟完成 benchmark
```

不要让用户在早期关卡等待太久。大模型关卡应明确提示耗时和发热风险。