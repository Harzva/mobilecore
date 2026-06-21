# TuiMa Push Custom Grid Spec

## 1. 目标

自定义格子系统让用户可以设计自己的 benchmark puzzle。

它不仅是装饰，而是把棋盘变成可分享的 benchmark 配置：

```text
棋盘布局
模型顺序
目标设备格
障碍条件
奖励规则
计分规则
```

## 2. 自定义棋盘基本结构

```json
{
  "board_id": "custom-8x8-demo",
  "name": "My 8x8 Device Challenge",
  "version": 1,
  "size": { "rows": 8, "cols": 8 },
  "theme": "pastel-mint",
  "device_preset": "smartphone",
  "score_rule": "sum_of_values",
  "tiles": []
}
```

## 3. 棋盘尺寸

MVP 支持：

```text
6x6
8x8
10x10
```

推荐默认：

```text
8x8
```

## 4. Tile 类型

### empty

空格。

```json
{ "type": "empty", "x": 0, "y": 0 }
```

### player_start

角色出生点。

```json
{ "type": "player_start", "x": 0, "y": 5 }
```

### model_box

模型箱子。

```json
{
  "type": "model_box",
  "x": 2,
  "y": 1,
  "model_tier": "3B",
  "model_id": "qwen2.5-3b-q4",
  "quantization": "Q4_K_M"
}
```

### target_phone

手机目标格。

```json
{
  "type": "target_phone",
  "x": 7,
  "y": 1,
  "accepts": ["0.5B", "1.5B", "3B"]
}
```

### wall

墙体或障碍。

```json
{ "type": "wall", "x": 4, "y": 2 }
```

### bonus_score

分数加成格。

```json
{ "type": "bonus_score", "x": 5, "y": 5, "value": 300 }
```

### speed_boost

速度加成格。

```json
{ "type": "speed_boost", "x": 1, "y": 7, "multiplier": 1.1 }
```

### upload_badge

上传徽章格。

```json
{ "type": "upload_badge", "x": 7, "y": 7 }
```

## 5. Tile Palette

编辑器底部组件栏应包含：

```text
0.5B Model
1.5B Model
3B Model
7B Model
14B Model
Target Phone
Wall Block
Bonus Score
Speed Boost
Upload Badge
Player Start
```

## 6. 设备预设

### Smartphone

默认手机设备。

### Tablet

平板设备，内存预算更高，目标格可更大。

### Custom

用户自定义内存预算、模型阈值。

## 7. 计分规则

### sum_of_values

所有跑通模型的分数累加。

### highest_tile

只看最高跑通模型档位，适合极限挑战。

### speed_run

更强调步数和速度。

### stability

更强调多次运行稳定性。

## 8. 棋盘校验规则

保存前必须校验：

```text
1. 至少一个 player_start
2. 至少一个 model_box
3. 至少一个 target_phone
4. 每个 model_box 有可达路径
5. 目标格数量 >= 必要模型数量
6. 棋盘尺寸合法
7. tile 坐标不越界
8. 同一个格子不能有冲突 tile
```

## 9. 分享结构

自定义棋盘可导出为：

```text
custom_board.json
```

也可以生成短链接：

```text
https://<user>.github.io/tuima-push/?board=<board_id>
```

## 10. 自定义棋盘排行榜

每个棋盘单独统计：

```text
board_id
board_version
score_rule
leaderboard_by_board
```

如果用户修改棋盘，应增加版本号：

```text
custom-board-abc:v1
custom-board-abc:v2
```

避免不同棋盘混在同一排行榜。

## 11. UI 页面结构

### 顶部

```text
Custom Grid
Design your own puzzle
Score badge
Profile / Settings
```

### 主体

```text
Board Editor
8x8 grid
右侧设置面板
```

### 底部

```text
Tile Palette
Preview
Save
Share
Board Info
```

## 12. MVP 范围

MVP 支持：

```text
8x8 棋盘
拖拽放置 tile
保存到 localStorage
导出 JSON
导入 JSON
上传 custom_boards 到 Supabase
```

后续支持：

```text
多人共享棋盘
棋盘点赞
棋盘难度评级
棋盘排行榜
棋盘模板库
```