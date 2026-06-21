# NEXT CODEX PROMPT - TuiMa Push Game MVP

请在本地项目中继续实现 TuiMa Push Game MVP。

项目路径：

```text
MobileCore/
```

## 背景

TuiMa Push 是 MobileCore / 推嘛体系下的游戏化本地大模型 benchmark。

核心概念：

```text
推箱子，但推的是大模型。
每个模型箱子被推到目标手机格后，代表这台手机跑通了该模型。
分数由模型规模、推理速度、延迟、内存效率、稳定性和温控组成。
结果可上传排行榜。
```

## 先阅读这些文件

```text
game/README.md
game/docs/game-product-plan.md
game/docs/gameplay-system.md
game/docs/scoring-and-benchmark.md
game/docs/leaderboard-data-architecture.md
game/docs/custom-grid-spec.md
game/docs/github-pages-deployment-plan.md
game/design/ui-screen-list.md
game/schemas/submission.schema.json
game/schemas/custom-board.schema.json
game/supabase/schema.sql
game/data/sample-board.json
game/data/sample-submission.json
```

## 本轮目标

创建一个可部署到 GitHub Pages 的静态 MVP 前端。

建议目录：

```text
game-web/
├── package.json
├── index.html
├── vite.config.ts
├── src/
│   ├── App.tsx
│   ├── config.ts
│   ├── styles.css
│   ├── game/
│   │   ├── board.ts
│   │   ├── levels.ts
│   │   ├── scoring.ts
│   │   └── mockBenchmark.ts
│   ├── data/
│   │   └── sampleBoard.ts
│   ├── components/
│   │   ├── BoardGrid.tsx
│   │   ├── ModelBox.tsx
│   │   ├── TargetPhone.tsx
│   │   ├── ScoreCard.tsx
│   │   └── LeaderboardTable.tsx
│   └── pages/
│       ├── Home.tsx
│       ├── Challenge.tsx
│       ├── CustomGrid.tsx
│       ├── Leaderboard.tsx
│       └── ResultUpload.tsx
└── README.md
```

## MVP 功能

### 1. Home

显示：

```text
TuiMa 推嘛
Push models on your phone
Start Challenge
Custom Grid
Leaderboard
0.5B → 1.5B → 3B → 7B → 14B → Phone ✓
```

### 2. Challenge

实现一个简单 8x8 推箱子棋盘。

必须支持：

```text
方向键 / WASD 移动
推动模型箱子
模型箱子到目标格后标记 cleared
Undo / Reset / Hint 可先做 UI，不强求完整逻辑
```

### 3. Mock Benchmark

在模型箱子到目标格后，先用 mock benchmark：

```text
0.5B: 90 tok/s
1.5B: 70 tok/s
3B: 45 tok/s
7B: 28 tok/s
14B: 12 tok/s
```

后续再接 MobileCore 本地 API。

### 4. Scoring

实现初版 scoring.ts：

```text
base score + speed score + completion bonus
```

### 5. Result Upload

先实现 localStorage：

```text
submissions 保存到浏览器本地
```

保留 Supabase 接口占位：

```text
SUPABASE_URL
SUPABASE_ANON_KEY
```

### 6. Leaderboard

从 localStorage 读取 submissions 并展示榜单。

### 7. Custom Grid

第一版只做 UI 和 JSON 导入/导出，不要求完整拖拽。

## 视觉要求

必须符合之前设计方向：

```text
简约
可爱
清新
TuiMa 推嘛
推箱子但推的是大模型
薄荷绿 / 天蓝 / 紫色 / 白色
圆角卡片
柔和阴影
```

## 不要做

```text
不要做暗黑赛博风
不要把品牌名重复堆太多次
不要上传真实隐私数据
不要直接暴露 GitHub 写 token
不要把 Supabase service role key 写前端
```

## 验收输出

完成后请输出：

```text
1. 新增文件列表
2. 如何本地运行
3. 如何部署 GitHub Pages
4. 当前已实现功能
5. 还缺哪些真实 MobileCore API 接入工作
```
