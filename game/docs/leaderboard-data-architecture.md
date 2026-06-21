# TuiMa Push Leaderboard & Data Architecture

## 1. 核心判断

GitHub Pages / github.io 只能托管静态前端页面，不能单独作为公共数据库。

因此：

```text
GitHub Pages = 游戏前端、排行榜展示、上传表单
Supabase / Firebase / Cloudflare D1 / GitHub Issues = 数据存储层
```

推荐第一阶段使用：

```text
GitHub Pages + Supabase
```

## 2. 推荐架构

```text
用户手机浏览器 / MobileCore WebView
        │
        ▼
GitHub Pages 静态前端
        │
        ├── 调用本地 MobileCore API
        │     http://127.0.0.1:8080/v1
        │
        ├── 获取 benchmark 结果
        │
        └── 上传结果
              ▼
          Supabase
              ├── submissions
              ├── custom_boards
              ├── leaderboard_view
              └── device_class_view
```

## 3. 为什么不用纯 GitHub JSON 写入

可以用 GitHub 仓库里的 JSON 文件展示排行榜，例如：

```text
data/leaderboard.json
```

但公开网页不能直接写 GitHub JSON，因为：

```text
1. 前端不能暴露 GitHub 写权限 token
2. 多人并发写 JSON 容易冲突
3. 更新需要 commit，实时性差
4. 容易被恶意提交污染
```

因此纯 JSON 只适合：

```text
只读展示
构建产物
备份快照
```

不适合公开投稿写入。

## 4. 数据流

### 4.1 游戏加载

```text
1. 打开 GitHub Pages 游戏页
2. 读取 config.js
3. 检查是否配置 Supabase
4. 若未配置，使用 localStorage demo 模式
5. 若已配置，使用公共排行榜模式
```

### 4.2 本地 Benchmark

```text
1. 游戏把模型箱子推到目标格
2. 前端调用 MobileCore 本地 API
3. MobileCore 执行 benchmark
4. 返回结果
5. 前端计算分数并生成 submission payload
```

### 4.3 上传结果

```text
1. 用户点击 Upload Result
2. 前端检查用户同意上传说明
3. 发送 submission 到 Supabase
4. Supabase RLS 允许匿名 insert
5. leaderboard_view 自动更新
6. 前端刷新排行榜
```

## 5. 上传字段

建议上传：

```json
{
  "player_name": "TuiStarter",
  "anonymous_id": "hash_xxx",
  "device_class": "iPhone 15 Pro",
  "chip_class": "A17 Pro",
  "os": "iOS",
  "runtime_backend": "llama.cpp",
  "mobilecore_version": "0.1.0",
  "board_id": "standard-device-challenge-04",
  "total_score": 12560,
  "avg_decode_tok_s": 28.4,
  "first_token_ms": 182,
  "memory_peak_mb": 5200,
  "best_model": "14B",
  "cleared_models": ["0.5B", "1.5B", "3B", "7B", "14B"]
}
```

不上传：

```text
真实姓名
手机号
邮箱
序列号
完整 IP
用户 prompt
用户文件内容
API Key
本地路径
```

## 6. 排行榜维度

### Global

全球所有提交排序。

### Friends

后续可通过邀请码、房间码或轻量账号实现。

### This Device Class

按设备等级分类：

```text
iPhone 15 Pro
Snapdragon 8 Gen 3
Dimensity 9300
Tablet
Unknown Android 12GB
```

### Board Ranking

每个自定义棋盘独立排行榜。

## 7. 数据表设计

核心表：

```text
submissions
custom_boards
```

视图：

```text
leaderboard_global
leaderboard_by_device_class
leaderboard_by_board
```

## 8. 安全策略

Supabase RLS 初版：

```text
允许匿名读取 leaderboard
允许匿名插入 submissions
禁止匿名更新 submissions
禁止匿名删除 submissions
custom_boards 允许读取和插入
```

正式版加入：

```text
rate limit
captcha
result signature
pending_review
abuse filter
```

## 9. 本地 API CORS

GitHub Pages 页面要调用本地 MobileCore：

```text
https://<user>.github.io/<repo>
  ↓
http://127.0.0.1:8080/v1
```

MobileCore 本地服务需要允许：

```text
Access-Control-Allow-Origin: https://<user>.github.io
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
```

开发阶段可以允许：

```text
http://localhost:5173
http://127.0.0.1:5173
```

正式版不要默认允许 `*`。

## 10. GitHub-only 备选方案

如果暂时不用 Supabase，可以做：

```text
GitHub Issues + GitHub Actions
```

流程：

```text
1. 用户提交结果表单
2. 前端生成 GitHub Issue URL 或调用 GitHub App
3. GitHub Action 定时读取 issues
4. 校验后写入 data/leaderboard.json
5. GitHub Pages 重新部署
```

优点：

```text
完全 GitHub 化
提交透明
可审计
```

缺点：

```text
实时性差
需要 GitHub 登录或 GitHub App
并发和反作弊更复杂
```

## 11. 推荐结论

第一阶段：

```text
GitHub Pages + Supabase
```

第二阶段：

```text
MobileCore App 内嵌 TuiMa Push
```

第三阶段：

```text
MobileCore 签名 benchmark 结果 + Supabase 上传 + GitHub Pages 展示
```