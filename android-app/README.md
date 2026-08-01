# TuiMa / MobileCore Android (0.1.3-rc2)

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

在主页面点击 `启动服务` 控制本机服务，点击 `导入` 可以通过系统文件选择器导入模型到 app 私有 `files/models/`，导入完成后会触发一次 `loadModel`。`检测` tab 提供 Quick、Standard、Stress 三档 TuiMa v2 跑分：校验冻结模型和提示词，采集 tok/s、TTFT、RAM、电池与 Android thermal 状态，输出 0–1000 标准分和 0–1,000,000 展示分。

启动后验证：

```bash
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/v1/models
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-model","messages":[{"role":"user","content":"Hello"}],"max_tokens":32}'
```

### 0.1.3-rc2 release APK

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

# 查看内置 ModelScope GGUF alias
./scripts/download-gguf.sh --list

# 先预览精选档位，不下载
./scripts/download-gguf.sh --all-modelscope --tier tiny --dry-run
./scripts/download-gguf.sh --all-modelscope --tier recommended --max-params-b 4 --dry-run
./scripts/download-gguf.sh --all-modelscope --max-params-b 9 --dry-run

# 单个 ModelScope 模型下载
./scripts/download-gguf.sh --provider modelscope --alias gemma3-270m-q4km
./scripts/download-gguf.sh --provider modelscope --alias gemma3-1b-q4km
./scripts/download-gguf.sh --provider modelscope --alias qwen2.5-0.5b-q4km

# 批量下载需要显式 --yes，避免误下大文件
./scripts/download-gguf.sh --all-modelscope --tier tiny --yes
./scripts/download-gguf.sh --all-modelscope --tier recommended --max-params-b 4 --yes

# 下载后直接推送到已安装 app，并触发 loadModel
./scripts/download-gguf.sh --provider modelscope --alias gemma3-270m-q4km --push --load
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

### Qwen2.5-Omni-3B 本地多模态边界

MobileCore 的 llama.cpp/libmtmd 路线只提供 `text/image/audio -> text`，不支持 video input，也不支持 audio/speech output。所选模型是 `ggml-org` 发布的 GGUF conversion，不是“Qwen 官方 GGUF”。完整安装需要主模型 Q4_K_M 与 Q8 mmproj 两个文件，总计 3,642,962,976 bytes，不能宣传成“约 2 GB 完整多模态”。

`GET /health` 会按输入/输出模态分别报告当前已加载 runtime 的真实能力，同时返回 active model、quantization、llama.cpp revision、两个 artifact 的 SHA-256/校验状态以及内存和存储预检。安装生命周期保持 app-private，并要求明确同意、source-declared license ID、默认 Wi-Fi-only、临时文件、校验、取消和卸载：

```bash
curl http://127.0.0.1:8080/health
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/mobilecore/omni/status
curl -X POST http://127.0.0.1:8080/mobilecore/omni/install \
  -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"explicit_consent":true,"accepted_license_id":"qwen-research","wifi_only":true}'
```

安装是异步操作，客户端轮询 `status`。另有 authenticated `cancel`、`verify`、`load`、`uninstall` 路由。不要在脚本、CI 或首次启动时自动调用 `install`；必须先向用户展示 3.64 GB 总量及 `source_declared_not_legal_reviewed` 状态并取得明确同意。详见 [`../docs/qwen25-omni-3b-compatibility.md`](../docs/qwen25-omni-3b-compatibility.md) 与 [`../docs/mobilecode-local-multimodal-contract.md`](../docs/mobilecode-local-multimodal-contract.md)。

## 4. Phone Agent / VLM Model Candidates

PhoneBuddy 这类手机 agent / 多模态模型不要放进 GGUF LLM catalog。`PhoneBuddyAI/PhoneBuddy-4B` 是 Hugging Face safetensors / BF16 / `qwen3_5` / image-text-to-text 模型，约 4.54B 参数、权重索引约 8.7GB；当前 Android runtime 只支持 GGUF 文本模型、ONNX/TFLite 视觉模型和 diffusion readiness gate，不能直接加载这个 checkpoint。

当前候选清单在 `model-downloads/phone-agent-catalog.tsv`。初步探测结果：

- `PhoneBuddyAI/PhoneBuddy-4B` 和 `PhoneBuddyAI/PhoneBuddy-4B-RealApp` 都是约 8.7GB safetensors 权重，不是单文件 GGUF。
- `PhoneBuddyAI/PhoneBuddy-0.8B` 更适合作为首个兼容性 probe，但同样是 `qwen3_5` / VLM 形态。
- `transformers==4.57.6` 的 `AutoConfig` 不能识别 `model_type=qwen3_5`；`AutoProcessor` 还需要 PyTorch/Torchvision 视觉栈。
- Hugging Face model card 给出的可行部署方向是 vLLM / SGLang / Docker Model Runner；MobileCore 若要接入，需要先新增远端 OpenAI-compatible proxy，或把 PhoneBuddy/Qwen3.5-VL 转成 Android 可用 runtime。

Minimal non-weight compatibility probe:

```bash
python3 -m venv /tmp/phonebuddy-probe
/tmp/phonebuddy-probe/bin/python -m pip install "transformers==4.57.6" huggingface_hub pillow
/tmp/phonebuddy-probe/bin/python - <<'PY'
from transformers import AutoConfig, AutoProcessor

repo = "PhoneBuddyAI/PhoneBuddy-4B"
for name, call in [
    ("AutoConfig", lambda: AutoConfig.from_pretrained(repo, trust_remote_code=False)),
    ("AutoProcessor", lambda: AutoProcessor.from_pretrained(repo, trust_remote_code=False)),
]:
    try:
        print(name, type(call()))
    except Exception as error:
        print(name, type(error).__name__, str(error)[:240])
PY
```

Do not claim PhoneBuddy is deployed in MobileCore until one of these is true:

- A GGUF/llama.cpp-compatible text-only conversion exists and passes `/mobilecore/model/load` + `/v1/chat/completions`.
- A VLM runtime is integrated and can run `qwen3_5` image-text prompts.
- A remote vLLM/SGLang endpoint is configured through a separate provider adapter without storing tokens in the repo.

## 5. Vision / Diffusion Model Candidates

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
- 扩散模型当前只暴露 readiness gate，不声称已完成图片生成。`/vision/diffusion` 会在缺少模型时返回 `model_missing`；导入 `.mnn` 后，如果没有 MNN-Diffusion native pipeline，会返回 `runtime_not_installed`；如果 ONNX backend 可加载但尚未实现 tokenizer / scheduler / UNet loop / VAE decode，会返回 `pipeline_not_implemented`。

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

curl -X POST -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a small mobilecore smoke image","width":512,"height":512,"steps":4,"seed":42}' \
  "http://127.0.0.1:8080/vision/diffusion"
```

CLIP / CIFAR10 / MNIST 路线：

- `CIFAR10`：已支持 TFLite 小 CNN 直接分类；也支持 ONNX Runtime Mobile 跑 CLIP image encoder + 预计算 CIFAR10 text embeddings，做 zero-shot 排序。
- `MNIST`：已支持小 CNN/TFLite 最小推理；CLIP 可演示但不作为首选。

首批建议只做 MNN Stable Diffusion 1.5：

- `sd15-mnn-opencl`：`MNN/stable-diffusion-v1-5-mnn-opencl`，约 1.1GB，优先用于 Android GPU/OpenCL 路线。
- `sd15-mnn-gpu`：`MNN/stable-diffusion-v1-5-mnn-gpu`，约 1.1GB，用于和 OpenCL 包做设备兼容对比。
- `sd15-mnn-cpu`：`MNN/stable-diffusion-v1-5-mnn`，约 2.2GB，主要作为正确性/CPU fallback baseline。

这些 ModelScope 仓库是资源包形态，包含 `text_encoder.mnn`、`unet.mnn`、`vae_decoder.mnn`、权重文件、tokenizer/merges/vocab/alphas 等文件；它们不能像单个 GGUF 或单个分类 `.onnx` 一样直接导入后运行。真正跑通 Stable Diffusion 还需要接入 MNN-Diffusion native runtime、资源包下载/校验、生成输出目录和模拟器/真机耗时内存 QA。

研究候选包括 `BK-SDM Tiny`、`LCM Dreamshaper int8/OpenVINO`、`Dreamshaper LCM ONNX` 和 `SD-Turbo`。这些不应进入 0.1.2 首发下载队列，除非先完成 Android 端转换、加载、内存和耗时验收。

## 6. Real Benchmark Metrics

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

TuiMa v2 使用冻结的 `Qwen/Qwen2.5-0.5B-Instruct-GGUF` Q4_K_M 文件和版本化提示词。Quick 用于快速检查，Standard 是可比较的正式档，Stress 用于持续性能检查。最新结构化报告和本机历史报告可通过以下接口读取：

```bash
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/v1/benchmark/latest
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/v1/benchmark/reports?limit=10
```

跑分前会检查电量、充电状态、thermal 状态、剩余存储、模型 SHA-256、提示词 SHA-256 和本机 runtime。门禁失败、超时或运行时故障会保存为无分数的 typed invalid report，不会伪造分数。

## 7. Leaderboard

旧版 v1 本机榜继续保留用于兼容历史记录；TuiMa v2 报告不需要网络，完成或失败后都会写入 app 私有存储：

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

## 8. 与 MobileCode 的连接

默认地址保持 `http://127.0.0.1:8080/v1`。

## 9. Recommendation API

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

## 10. Oxford-Pets / G2D 端侧验证

G2D 正式验证固定使用 Oxford-IIIT Pets 官方 `annotations/test.txt`：37 类、3,669 张，不重新随机划分。运行档位为每类 1 张（37）、每类 10 张（370）和完整测试集（3,669）。CIFAR-10 只保留为视觉运行时的快速工程回归。

官方下载地址：

- `https://www.robots.ox.ac.uk/~vgg/data/pets/data/annotations.tar.gz`
- `https://www.robots.ox.ac.uk/~vgg/data/pets/data/images.tar.gz`

仅校验官方标注协议时：

```bash
./gradlew testDebugUnitTest \
  --tests 'ai.mobilecore.g2d.OxfordPetsOfficialDatasetIntegrationTest' \
  -PoxfordPetsTestSplit=/absolute/path/to/annotations/test.txt \
  -PoxfordPetsImages=/absolute/path/to/images
```

验证页对照五种策略：`CLIP-only`、`VLM-only`、`G2D 1θ`、`G2D 2θ`、`Agentic G2D`。Agentic 版本把 `clip_direct`、`vlm_full_labels`、`candidate_verifier`、`candidate_verifier_no_prob` 注册为封闭工具；本地小模型只做工具选择，不接收测试真值。非法或不可用工具调用会记录并回退到 2θ 规则基线。

## 11. 下一步

1. 为 `loadModel` 增加加载中/加载失败状态查询，避免大模型加载时客户端只能等待。
2. 把当前 greedy decode 扩展成可配置 sampler，并补齐 stream 模式。
3. 根据设备能力补 ABI、线程数和 context length 配置。
4. 接入 Supabase 匿名读写共享排行榜，并保留本机榜离线可用。
5. 未来接可选 Google 登录，用于共享排行榜、云同步和个人资料；本地推理、模型导入和 localhost API 不依赖登录。
