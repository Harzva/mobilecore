# TuiMa Push Game 产品方案

## 1. 背景

MobileCore / 推嘛的核心能力是让手机本地运行大模型，并提供本地 API、模型管理和跑分能力。传统 benchmark 往往像工具表格，用户理解成本高，也缺少传播性。

TuiMa Push Game 将本地 LLM benchmark 做成可玩的推箱子游戏：

```text
推得动哪个模型箱子，说明这台设备能跑哪个模型。
```

这让用户能用游戏化方式理解：

- 我的手机能跑多大模型？
- 速度怎么样？
- 内存是否够？
- 温度和稳定性如何？
- 我的设备在排行榜上处于什么水平？

## 2. 产品命名

推荐名称：

```text
英文：TuiMa Push
中文：推嘛模型挑战 / 推嘛推箱子
```

完整表达：

```text
TuiMa Push — On-device LLM benchmark game
```

中文表达：

```text
推嘛模型挑战：把手机本地大模型跑分做成推箱子游戏
```

## 3. 一句话定位

> Push model boxes and discover what your phone can run.

中文：

> 推动模型箱子，看看你的手机能跑多大模型。

## 4. 核心用户

### 4.1 普通 AI 用户

想知道自己的手机能不能离线跑模型，但不懂参数、量化、推理速度。

### 4.2 本地模型玩家

喜欢比较不同设备、不同模型、不同量化版本的表现。

### 4.3 开发者

希望用游戏化方式快速测试设备是否适合做本地 AI runtime。

### 4.4 MobileCore 用户

已经安装 MobileCore，希望通过轻量互动获取模型推荐和排行榜。

## 5. 核心价值

```text
游戏化：降低 benchmark 理解成本
可传播：分数、徽章、排行榜适合分享
可验证：每个模型箱子背后是真实本地推理
可扩展：支持自定义格子和自定义关卡
可部署：前端可部署到 GitHub Pages
```

## 6. 产品结构

```text
TuiMa Push Game
├── Home / 游戏首页
│   ├── Start Challenge
│   ├── Custom Grid
│   └── Leaderboard
│
├── Standard Challenge / 标准挑战
│   ├── 0.5B
│   ├── 1.5B
│   ├── 3B
│   ├── 7B
│   ├── 14B
│   └── 32B+
│
├── Gameplay / 推箱子棋盘
│   ├── 模型箱子
│   ├── 手机目标格
│   ├── 墙体/障碍
│   ├── 奖励格
│   └── 上传徽章
│
├── Custom Grid / 自定义格子
│   ├── 棋盘尺寸
│   ├── 模型箱子组件
│   ├── 目标格组件
│   ├── 计分规则
│   └── 分享 JSON
│
├── Result Upload / 结果上传
│   ├── 设备信息
│   ├── 分数
│   ├── 平均速度
│   ├── 跑通模型
│   └── 隐私说明
│
└── Leaderboard / 排行榜
    ├── Global
    ├── Friends
    ├── This Device Class
    └── Custom Board Ranking
```

## 7. 关键页面

### 7.1 游戏首页

核心文案：

```text
Push models on your phone
Play, push, and discover which models your phone can run.
```

按钮：

```text
Start Challenge
Custom Grid
Leaderboard
```

展示：

```text
0.5B → 1.5B → 3B → 7B → 14B → 手机目标格
```

### 7.2 设备挑战页

展示当前关卡、棋盘、模型箱子、目标手机格、分数、移动步数、推理速度奖励。

### 7.3 自定义棋盘页

用户可以拖拽：

```text
模型箱子
目标手机格
墙体
奖励格
上传徽章
速度加成格
```

### 7.4 上传结果页

展示：

```text
Challenge Complete
Total Score
Average Speed
Best Cleared Model
Stages Completed
Upload Result
```

### 7.5 排行榜页

展示：

```text
Rank
Player & Device
Total Score
Avg Speed
Cleared Models
```

## 8. 部署目标

第一阶段：

```text
GitHub Pages + Supabase
```

第二阶段：

```text
MobileCore App 内嵌游戏页面
```

第三阶段：

```text
真实本地模型 benchmark + 签名结果上传
```

## 9. 成功标准

```text
1. 用户能理解游戏规则
2. 用户能完成标准关卡
3. 用户能看到模型通关进度
4. 用户能上传结果
5. 排行榜能实时更新
6. 自定义棋盘能保存和分享
7. 后续能接入 MobileCore 真实推理数据
```