# MobileCore Android Skeleton (v0.1)

本文件记录 Android 原生 MVP 骨架的本地启动方式。当前 APK 会编译并加载 `mobilecore_llama` JNI library，已链接 llama.cpp，并提供 OpenAI-compatible mock/fallback 路由、模型目录发现和最小 GGUF 加载入口。

## 1. 目录

```text
android-app/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/...
      kotlin/ai/mobilecore/runtime/
      kotlin/ai/mobilecore/network/
      kotlin/ai/mobilecore/service/
      cpp/

```

## 2. 运行骨架

```bash
cd android-app
./scripts/bootstrap-llama.sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mobilecore.app/ai.mobilecore.MainActivity
```

在主页面点击 `启动服务` 控制本机服务，点击 `导入` 可以通过系统文件选择器导入模型到 app 私有 `files/models/`，导入完成后会触发一次 `loadModel`。`检测` tab 里的 `开始检测` 会执行一键 smoke test：启动服务、加载模型、发送本机 chat、读取 `/metrics`，并记录 tok/s、TTFT、总耗时和本机排行榜分数。

启动后验证：

```bash
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/v1/models
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-model","messages":[{"role":"user","content":"Hello"}],"max_tokens":32}'
```

### 0.1.2 RC release APK

常规 release 构建默认不签名，产物用于 CI 或后续正式签名：

```bash
./gradlew :app:assembleRelease
ls -lh app/build/outputs/apk/release/app-release-unsigned.apk
```

真机/模拟器内测安装时，可以显式使用 debug keystore 生成可安装的 release APK。这个开关只用于本地 RC 验收，不代表商店发布签名：

```bash
./gradlew :app:assembleRelease -Pmobilecore.debugSignedRelease=true
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.mobilecore.app/ai.mobilecore.MainActivity
```

## 3. 导入 / 推送 GGUF 并加载

最小路径有两种：

1. App 内点击 `Import GGUF`，选择 `.gguf` 文件，应用会复制到私有 `files/models/` 并触发加载。
2. 使用下载脚本从 Hugging Face 或 ModelScope 拉取小模型，再 push 到 app external models directory:

Models tab 也内置了 ModelScope 模型站入口：应用会通过 ModelScope `suggestv2` 搜索 GGUF 仓库，再读取仓库详情和 `repo/files` 文件列表，展示 GGUF 文件、参数量、量化等级、架构、大小和下载量。搜索框支持 `qwen3`、`q4`、`0.6B`、`Q4_K_M` 等关键词；空搜索会保留默认小模型推荐。点击 `Download` 会走应用内下载器并在完成后触发加载。

```bash
# 可选：安装 Hugging Face / ModelScope 下载工具到本地 .tools/
./scripts/download-gguf.sh --install-tools
source .tools/model-downloaders/bin/activate

# 查看内置 10 个 ModelScope GGUF alias
./scripts/download-gguf.sh --list

# 先预览精选档位，不下载
./scripts/download-gguf.sh --all-modelscope --tier tiny --dry-run
./scripts/download-gguf.sh --all-modelscope --tier recommended --max-params-b 4 --dry-run
./scripts/download-gguf.sh --all-modelscope --max-params-b 9 --dry-run

# 单个 ModelScope 模型下载
./scripts/download-gguf.sh --provider modelscope --alias qwen2.5-0.5b-q4km

# 批量下载需要显式 --yes，避免误下大文件
./scripts/download-gguf.sh --all-modelscope --tier tiny --yes
./scripts/download-gguf.sh --all-modelscope --tier recommended --max-params-b 4 --yes

# 下载后直接推送到已安装 app，并触发 loadModel
./scripts/download-gguf.sh --provider modelscope --alias qwen2.5-0.5b-q4km --push --load
```

手动 push 也可以：

```bash
adb shell mkdir -p /sdcard/Android/data/com.mobilecore.app/files/models
adb push /path/to/model.gguf /sdcard/Android/data/com.mobilecore.app/files/models/
```

随后可以在 app 内点击模型卡片的 `加载`，也可以通过本机 API 触发加载：

```bash
adb forward tcp:8080 tcp:8080
curl -X POST http://127.0.0.1:8080/mobilecore/model/load \
  -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"context_length":2048}'
```

Useful discovery endpoint:

```bash
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/mobilecore/models/dirs
```

## 4. Vision / Diffusion Model Candidates

扩散模型不要放进 GGUF LLM catalog；它们需要 MNN、ONNX Runtime Mobile 或 TFLite/NCNN 这样的视觉推理后端。当前候选清单在 `model-downloads/diffusion-catalog.tsv`。

Android app 已新增独立 `视觉` tab。当前 RC 支持通过系统文件选择器选择图片，并把图片复制到 app 私有工作区。APK 已打包 ML Kit OCR、ONNX Runtime Android 与 TensorFlow Lite，用于本地视觉模型加载探测和最小推理闭环。后续接入建议：

- 首选 `RapidOCR / PP-OCR`：ONNX Runtime Mobile，中文/英文场景都更实用。
- 备选 `PaddleOCR` 小模型：检测 + 识别两段式，适合做更完整 OCR pipeline。
- 研究 `TrOCR tiny`：适合文档/印刷体评测，不建议作为第一版默认 OCR。

视觉模型保持独立页面和独立后端，不进入 GGUF LLM catalog，也不影响本机 LLM 服务。

本机视觉接口已经预留。App 选择图片后会复制到私有 `files/vision/images/` 工作区；`视觉` tab 的 `导入模型` 可以导入 `.onnx`、`.ort`、`.tflite`、`.mnn` 和 CLIP sidecar `.json` 到 app 私有 `vision/models/` 目录。也可以用 `复制视觉模型目录` 获取 adb 目标路径手动 push。接口会读取本地图片文件并返回宽高/大小元数据，并对 ONNX/TFLite 模型做真实加载探测：

- 没有模型：返回 `models_missing` / `model_missing`。
- 模型文件无效或不兼容：返回 `model_load_error`，错误信息只保留文件名，不暴露完整本机路径。
- 模型可加载：返回 `backend_ready`。OCR 使用 ML Kit on-device path；MNIST/CIFAR10 TFLite 会执行真实推理；CLIP 支持 image encoder + 预计算文本 embedding sidecar 做 CIFAR10 zero-shot 排序。
- MNIST TFLite 小模型已接入最小真实推理：要求输入张量总元素数为 `784`，输出总元素数为 `10`，支持 `FLOAT32` / `UINT8`。接口会把图片缩放成 `28x28` 灰度后返回 `status=ok`、`label`、`confidence` 和 10 类分数；形状不匹配时返回 `unsupported_model_shape`。
- CIFAR10 TFLite 小模型已接入最小真实推理：要求 RGB 图像输入和 10 类输出，支持 `FLOAT32` / `UINT8` / `INT8`。接口会返回 CIFAR10 label、confidence 和分数列表。
- CLIP zero-shot 路径要求 `.onnx` image encoder，并在同目录放置 `cifar10-text-embeddings.json` 这类 sidecar。sidecar 最小格式：

```json
[
  {"label": "airplane", "embedding": [0.01, -0.02]},
  {"label": "automobile", "embedding": [0.03, 0.04]}
]
```

支持的 sidecar 文件名包括 `<clip-model>-cifar10-embeddings.json`、`<clip-model>-cifar10-text-embeddings.json`、`cifar10-text-embeddings.json`、`clip-cifar10-embeddings.json`、`clip-cifar10-text-embeddings.json`。缺少 sidecar 时接口返回 `text_embeddings_missing`，不会伪装成已完成分类。

```bash
curl -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/vision/status"

curl -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/vision/models"

curl -X POST -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"image_name":"sample.png","image_path":"/data/user/0/com.mobilecore.app/files/vision/images/sample.png"}' \
  "http://127.0.0.1:8080/vision/ocr"

curl -X POST -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"image_name":"sample.png","image_path":"/data/user/0/com.mobilecore.app/files/vision/images/sample.png","dataset":"cifar10"}' \
  "http://127.0.0.1:8080/vision/classify"

curl -X POST -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"image_name":"digit.png","image_path":"/data/user/0/com.mobilecore.app/files/vision/images/digit.png","dataset":"mnist"}' \
  "http://127.0.0.1:8080/vision/classify"
```

CLIP / CIFAR10 / MNIST 路线：

- `CIFAR10`：已支持 TFLite 小 CNN 直接分类；也支持 ONNX Runtime Mobile 跑 CLIP image encoder + 预计算 CIFAR10 text embeddings，做 zero-shot 排序。
- `MNIST`：已支持小 CNN/TFLite 最小推理；CLIP 可演示但不作为首选。

首批建议只做 MNN Stable Diffusion 1.5：

- `sd15-mnn-opencl`：`MNN/stable-diffusion-v1-5-mnn-opencl`，约 1.1GB，优先用于 Android GPU/OpenCL 路线。
- `sd15-mnn-gpu`：`MNN/stable-diffusion-v1-5-mnn-gpu`，约 1.1GB，用于和 OpenCL 包做设备兼容对比。
- `sd15-mnn-cpu`：`MNN/stable-diffusion-v1-5-mnn`，约 2.2GB，主要作为正确性/CPU fallback baseline。

研究候选包括 `BK-SDM Tiny`、`LCM Dreamshaper int8/OpenVINO`、`Dreamshaper LCM ONNX` 和 `SD-Turbo`。这些不应进入 0.1.2 首发下载队列，除非先完成 Android 端转换、加载、内存和耗时验收。

## 5. Real Benchmark Metrics

After a non-streaming chat call, `/metrics` exposes the latest native llama.cpp timing:

```bash
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/metrics
```

The response includes:

- `last_prompt_eval_ms`
- `last_first_token_ms`
- `last_decode_ms`
- `last_total_ms`
- `last_decode_tokens_per_second`
- `last_prompt_tokens`
- `last_completion_tokens`

The same timing fields are also returned under `mobilecore` in `/v1/chat/completions`.

## 6. Leaderboard

本机榜不需要网络，`开始检测` 完成后会写入 app 私有存储：

```bash
curl -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/leaderboard/local?limit=10"
```

共享榜使用 Supabase PostgREST 匿名读写。仓库只提交空模板 `app/src/main/assets/supabase_leaderboard.json`，不要把真实 anon key 写进 git。内测机可以把配置放到 app external files 目录：

```json
{
  "url": "https://YOUR_PROJECT.supabase.co",
  "anon_key": "YOUR_SUPABASE_ANON_KEY",
  "table": "mobilecore_leaderboard"
}
```

```bash
adb shell mkdir -p /sdcard/Android/data/com.mobilecore.app/files
adb push mobilecore_supabase.json /sdcard/Android/data/com.mobilecore.app/files/mobilecore_supabase.json
```

推荐表结构：

```sql
create table if not exists public.mobilecore_leaderboard (
  run_id text primary key,
  spec_id text not null,
  created_at_ms bigint not null,
  device_name text not null,
  model_id text not null,
  quantization text not null default 'unknown',
  model_size_bytes bigint not null default 0,
  score_total integer not null default 0,
  score_speed integer not null default 0,
  score_response integer not null default 0,
  score_memory integer not null default 0,
  score_stability integer not null default 0,
  load_time_ms bigint not null default 0,
  decode_tokens_per_second double precision not null default 0,
  first_token_ms bigint not null default 0,
  total_ms bigint not null default 0,
  memory_peak_mb bigint not null default 0,
  inserted_at timestamptz not null default now()
);

alter table public.mobilecore_leaderboard enable row level security;

create policy "public leaderboard read"
on public.mobilecore_leaderboard for select
to anon
using (true);

create policy "anonymous benchmark insert"
on public.mobilecore_leaderboard for insert
to anon
with check (spec_id = 'mobilecore-benchmark-v1');

create policy "anonymous benchmark update"
on public.mobilecore_leaderboard for update
to anon
using (spec_id = 'mobilecore-benchmark-v1')
with check (spec_id = 'mobilecore-benchmark-v1');
```

接口：

```bash
curl -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/leaderboard/shared?limit=10"

curl -X POST -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/leaderboard/shared?limit=10"
```

## 7. 与 MobileCode 的连接

默认地址保持 `http://127.0.0.1:8080/v1`。

## 8. Recommendation API

推荐接口会综合设备探测、GGUF 元数据、偏好权重和最近一次 benchmark 结果：

```bash
curl -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/v1/recommendations?preference=speed"
curl -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/v1/recommendations?preference=stability"
curl -H "Authorization: Bearer local" \
  "http://127.0.0.1:8080/v1/recommendations?preference=small"
```

评分参数位于 `app/src/main/assets/recommendation_scoring.json`，UI 首页的偏好滑块会映射到这三档。

## 9. 下一步

1. 为 `loadModel` 增加加载中/加载失败状态查询，避免大模型加载时客户端只能等待。
2. 把当前 greedy decode 扩展成可配置 sampler，并补齐 stream 模式。
3. 根据设备能力补 ABI、线程数和 context length 配置。
4. 接入 Supabase 匿名读写共享排行榜，并保留本机榜离线可用。
5. 未来接可选 Google 登录，用于共享排行榜、云同步和个人资料；本地推理、模型导入和 localhost API 不依赖登录。
