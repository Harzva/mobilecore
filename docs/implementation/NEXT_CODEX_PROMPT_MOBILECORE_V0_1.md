# NEXT CODEX PROMPT - MobileCore v0.1

请在本地项目中继续实现 MobileCore v0.1。

项目路径：

```text
MobileCore/
```

## 背景

MobileCore（推嘛 / TuiMa）是 Mobile AI Stack 的本地模型运行时层。MobileCode 是上层 Agent Harness；MobileCore 是下层 Local LLM Runtime。

重要边界：

```text
正式版不要依赖 Termux。
Termux 只作为 Android P0 Probe。
正式 Runtime 依赖 llama.cpp + GGUF，并分别走 Android/iOS 原生集成。
```

## 已有文档

请先阅读：

```text
README.md
ROADMAP.md
docs/mobile-ai-stack.md
docs/mobilecore-core-architecture.md
docs/mobilecore-api-contract.md
docs/implementation/mobilecore-v0.1-implementation-brief.md
src/runtime-core/runtime-contract.md
src/android-native/ANDROID_NATIVE_LLAMA_BACKEND.md
src/ios-native/IOS_NATIVE_LLAMA_BACKEND.md
examples/mobilecode-provider/mobilecore-provider-preset.json
```

## 本轮目标

先不要做完整产品 UI。请创建一个最小 Android Native Demo 骨架：

```text
android-app/
  app/
  README.md
  build.gradle / settings.gradle 或等价 Gradle 骨架
  src/main/java/.../MobileCoreService.kt
  src/main/java/.../ModelManager.kt
  src/main/java/.../LocalApiServer.kt
  src/main/java/.../RuntimeTypes.kt
  src/main/cpp/mobilecore_llama_bridge.cpp
  src/main/cpp/CMakeLists.txt
```

## 要求

1. 先实现代码骨架，不要求真实编译通过 llama.cpp。
2. C++ bridge 中用 TODO 标出接入 llama.cpp 的位置。
3. Kotlin 层要体现 RuntimeBackend 抽象。
4. LocalApiServer 要体现 `/v1/models` 与 `/v1/chat/completions` 两个路由。
5. README 说明如何从骨架升级到真实 llama.cpp NDK 集成。
6. 不要引入 Termux 作为正式依赖。
7. 保留 MobileCode provider 对接方式：OpenAI-compatible API。

## 验收

完成后输出：

```text
- 新增文件列表
- 当前能做什么
- 还缺什么
- 下一步如何接入真实 llama.cpp
```
