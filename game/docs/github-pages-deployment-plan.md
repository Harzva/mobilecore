# GitHub Pages Deployment Plan

## 1. 目标

将 TuiMa Push Game 部署为公开访问的 GitHub Pages 网站：

```text
https://<github-username>.github.io/<repo-name>/
```

网站包含：

```text
游戏首页
标准挑战
自定义棋盘
排行榜
结果上传
说明文档
```

## 2. 推荐架构

```text
GitHub Repository
├── index.html
├── src/
├── public/
├── data/
├── config.js
└── dist/

GitHub Pages
  托管静态页面

Supabase
  存储提交记录和排行榜
```

## 3. 前端技术栈建议

推荐：

```text
Vite + React + TypeScript
```

原因：

```text
轻量
适合 GitHub Pages
组件化方便
排行榜和游戏状态好管理
后续可复用到 MobileCore WebView
```

可选：

```text
Vue + Vite
SvelteKit static export
纯 HTML/JS MVP
```

## 4. 目录建议

```text
game-web/
├── index.html
├── package.json
├── vite.config.ts
├── src/
│   ├── App.tsx
│   ├── config.ts
│   ├── routes/
│   │   ├── Home.tsx
│   │   ├── Challenge.tsx
│   │   ├── CustomGrid.tsx
│   │   ├── Leaderboard.tsx
│   │   └── ResultUpload.tsx
│   ├── game/
│   │   ├── board.ts
│   │   ├── scoring.ts
│   │   ├── benchmark.ts
│   │   └── levels.ts
│   ├── mobilecore/
│   │   ├── client.ts
│   │   └── cors-check.ts
│   ├── supabase/
│   │   ├── client.ts
│   │   └── queries.ts
│   └── components/
│       ├── BoardGrid.tsx
│       ├── ModelBox.tsx
│       ├── TargetPhone.tsx
│       ├── ScoreCard.tsx
│       └── LeaderboardTable.tsx
└── public/
    └── assets/
```

## 5. GitHub Pages 配置

### 5.1 vite.config.ts

如果仓库名是 `tuima-push`：

```ts
export default defineConfig({
  base: '/tuima-push/',
  plugins: [react()],
})
```

如果部署到用户主页根路径：

```ts
export default defineConfig({
  base: '/',
  plugins: [react()],
})
```

### 5.2 GitHub Actions

```yaml
name: Deploy GitHub Pages

on:
  push:
    branches: [main]

permissions:
  contents: read
  pages: write
  id-token: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - run: npm ci
      - run: npm run build
      - uses: actions/upload-pages-artifact@v3
        with:
          path: dist

  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    needs: build
    steps:
      - id: deployment
        uses: actions/deploy-pages@v4
```

## 6. Supabase 配置

前端配置：

```ts
export const SUPABASE_URL = 'https://xxxx.supabase.co'
export const SUPABASE_ANON_KEY = 'public-anon-key'
```

注意：

```text
anon key 可以公开，但必须依赖 RLS 限制权限。
不能把 service role key 放进前端。
```

## 7. Demo / Local 模式

如果没有配置 Supabase：

```text
使用 localStorage 保存 submissions
排行榜只在本地浏览器生效
页面显示 Demo Mode
```

如果配置 Supabase：

```text
共享排行榜模式
```

## 8. MobileCore 本地连接

前端默认测试：

```text
http://127.0.0.1:8080/v1/models
```

配置项：

```ts
export const MOBILECORE_BASE_URL = 'http://127.0.0.1:8080/v1'
export const MOBILECORE_API_KEY = 'local'
```

浏览器调用本地 API 要求 MobileCore 设置 CORS。

## 9. 上传流程

```text
1. 完成挑战
2. 生成 benchmark result
3. 用户确认上传
4. 插入 submissions
5. 刷新 leaderboard
```

## 10. 隐私提示

上传按钮附近必须写：

```text
Only device class, score, average speed, and cleared models are uploaded.
No prompt, file, API key, or personal data is uploaded.
```

中文：

```text
仅上传设备类别、分数、平均速度和跑通模型。不上传 Prompt、文件、API Key 或个人隐私数据。
```

## 11. GitHub-only 备选部署

如果不用 Supabase，可以：

```text
1. GitHub Pages 展示 game UI
2. Upload Result 生成 GitHub Issue
3. GitHub Actions 汇总 issues
4. 生成 data/leaderboard.json
5. 页面读取 JSON
```

但此方案适合低频提交，不适合实时排行榜。

## 12. MVP 执行顺序

```text
Step 1: 创建 game-web Vite 项目
Step 2: 做静态 UI
Step 3: 实现推箱子棋盘状态
Step 4: 实现 mock benchmark
Step 5: 实现 localStorage leaderboard
Step 6: 接 Supabase
Step 7: 配 GitHub Pages
Step 8: 接 MobileCore 本地 API
Step 9: 增加自定义棋盘
Step 10: 加入结果上传和排行榜筛选
```