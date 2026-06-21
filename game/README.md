# TuiMa Push Game（推嘛推箱子跑分游戏）

TuiMa Push 是 MobileCore / 推嘛体系下的游戏化本地大模型跑分方案。

它的核心不是普通推箱子，而是：

```text
推箱子 = 推大模型
箱子大小 = 模型规模
推到目标手机格 = 该设备跑通该模型
通关分数 = 模型规模 + 推理速度 + 稳定性 + 内存效率 + 温控表现
上传结果 = 更新排行榜
```

## 一句话定位

> TuiMa Push turns on-device LLM inference into a playful benchmark game.

中文表达：

> 推嘛把手机本地大模型推理，做成一个可玩、可比、可上传的推箱子跑分游戏。

## 产品关系

```text
TuiMa / 推嘛
  品牌层，面向用户的名字

MobileCore
  本地模型运行时，负责加载模型、推理、API、性能采集

TuiMa Push Game
  游戏化 benchmark 层，负责推箱子玩法、关卡、分数、排行榜
```

## 核心玩法

用户在棋盘上推动不同大小的模型箱子：

```text
0.5B → 1.5B → 3B → 7B → 14B → 32B+
```

每个模型箱子被推到目标手机格后，MobileCore 会真实执行一次本地推理测试：

```text
1. 加载对应模型或对应模型档位
2. 运行固定 benchmark prompt
3. 记录 decode tok/s、first-token latency、内存峰值、温度趋势、是否 OOM
4. 判定是否跑通
5. 根据结果点亮模型格子并计算分数
```

## 主要模块

```text
game/
├── README.md
├── docs/
│   ├── game-product-plan.md
│   ├── gameplay-system.md
│   ├── scoring-and-benchmark.md
│   ├── leaderboard-data-architecture.md
│   ├── custom-grid-spec.md
│   └── github-pages-deployment-plan.md
├── schemas/
│   ├── submission.schema.json
│   └── custom-board.schema.json
├── supabase/
│   └── schema.sql
├── data/
│   ├── sample-board.json
│   └── sample-submission.json
├── design/
│   └── ui-screen-list.md
└── prompts/
    └── NEXT_CODEX_PROMPT_GAME_MVP.md
```

## 推荐技术路线

第一阶段推荐：

```text
GitHub Pages 前端
  - 游戏说明页
  - 关卡选择页
  - 自定义棋盘页
  - 排行榜页

Supabase 数据层
  - submissions 提交记录
  - leaderboard 排行榜视图
  - custom_boards 自定义棋盘

MobileCore 本地运行时
  - 本地模型推理
  - benchmark 数据采集
  - 生成可上传结果
```

备选：

```text
GitHub Issues + GitHub Actions
  - 完全 GitHub 化
  - 数据透明可审计
  - 但实时性弱，不适合高频排行榜
```

## 最小 MVP

MVP 只需要先完成：

```text
1. 游戏首页
2. 标准挑战关卡
3. 自定义棋盘 JSON 结构
4. 本地 mock benchmark
5. 排行榜页面
6. Supabase 上传/读取
7. GitHub Pages 部署
```

后续再接入 MobileCore 的真实本地模型推理。