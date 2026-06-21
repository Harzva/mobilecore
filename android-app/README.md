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

在主页面点击 `Start Service` / `Stop Service` 控制服务。点击 `Import GGUF` 可以通过系统文件选择器导入模型到 app 私有 `files/models/`，导入完成后会触发一次 `loadModel`。

启动后验证：

```bash
curl -H "Authorization: Bearer local" http://127.0.0.1:8080/v1/models
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-model","messages":[{"role":"user","content":"Hello"}],"max_tokens":32}'
```

## 3. Import/Push GGUF and Load

最小路径有两种：

1. App 内点击 `Import GGUF`，选择 `.gguf` 文件，应用会复制到私有 `files/models/` 并触发加载。
2. 使用下载脚本从 Hugging Face 或 ModelScope 拉取小模型，再 push 到 app external models directory:

```bash
# 可选：安装 Hugging Face / ModelScope 下载工具到本地 .tools/
./scripts/download-gguf.sh --install-tools
source .tools/model-downloaders/bin/activate

# 查看内置小模型 alias
./scripts/download-gguf.sh --list

# Hugging Face 下载
./scripts/download-gguf.sh --provider hf --alias smollm2-135m-q4km

# ModelScope 下载，适合 Hugging Face 网络不稳定时使用
./scripts/download-gguf.sh --provider modelscope --alias qwen2.5-0.5b-q4km

# 下载后直接推送到已安装 app，并触发 loadModel
./scripts/download-gguf.sh --provider modelscope --alias qwen2.5-0.5b-q4km --push --load
```

手动 push 也可以：

```bash
adb shell mkdir -p /sdcard/Android/data/com.mobilecore.app/files/models
adb push /path/to/model.gguf /sdcard/Android/data/com.mobilecore.app/files/models/
```

Then either tap `Load First GGUF` in the app, or trigger loading through the local API:

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

## 4. Real Benchmark Metrics

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

## 5. 与 MobileCode 的连接

默认地址保持 `http://127.0.0.1:8080/v1`。

## 6. Recommendation API

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

## 7. 下一步

1. 将首页 `Runtime Snapshot` 接到真实 `/health`、`/metrics` 和模型扫描状态。
2. 为 `loadModel` 增加加载中/加载失败状态查询，避免大模型加载时客户端只能等待。
3. 把当前 greedy decode 扩展成可配置 sampler，并补齐 stream 模式。
4. 根据设备能力补 ABI、线程数和 context length 配置。
