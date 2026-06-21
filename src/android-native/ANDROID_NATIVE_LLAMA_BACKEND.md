# Android Native Llama Backend v0.1

## 1. 目标

Android 正式版 MobileCore 不依赖 Termux，而是通过 Android 原生 App 集成 llama.cpp。

```text
MobileCore Android App
  ↓ Kotlin / Java API Layer
  ↓ JNI / NDK Bridge
  ↓ llama.cpp native library
  ↓ GGUF model
```

## 2. P0 与正式版区别

```text
P0:
Android → Termux → llama-server → localhost API

正式版:
Android App → JNI/NDK → llama.cpp → in-app API server
```

Termux 只用于快速验证，不进入正式产品依赖。

## 3. Android 模块建议

```text
android-app/
├── app/
│   ├── src/main/java/.../MobileCoreService.kt
│   ├── src/main/java/.../ModelManager.kt
│   ├── src/main/java/.../LocalApiServer.kt
│   ├── src/main/java/.../BenchmarkRunner.kt
│   └── src/main/cpp/
│       ├── mobilecore_llama_bridge.cpp
│       ├── CMakeLists.txt
│       └── llama.cpp/ 或 submodule
```

## 4. Kotlin 层接口

```kotlin
interface MobileCoreRuntime {
    fun backendInfo(): BackendInfo
    suspend fun loadModel(path: String, options: LoadOptions): LoadResult
    suspend fun unloadModel(): Boolean
    fun isModelLoaded(): Boolean
    suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): ChatResult
    fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Flow<ChatToken>
    fun metrics(): RuntimeMetrics
}
```

## 5. JNI Bridge 初版

```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_ai_mobilecore_runtime_LlamaBridge_backendInfo(JNIEnv* env, jobject thiz);

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_mobilecore_runtime_LlamaBridge_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jint contextLength);

extern "C" JNIEXPORT jstring JNICALL
Java_ai_mobilecore_runtime_LlamaBridge_chat(JNIEnv* env, jobject thiz, jstring messagesJson, jstring optionsJson);

extern "C" JNIEXPORT void JNICALL
Java_ai_mobilecore_runtime_LlamaBridge_unloadModel(JNIEnv* env, jobject thiz);
```

## 6. Local API Server

Android 侧可内置一个轻量 HTTP server，只监听 localhost：

```text
127.0.0.1:8080/v1/models
127.0.0.1:8080/v1/chat/completions
```

候选实现：

- Ktor embedded server；
- NanoHTTPD；
- 自研极简 HTTP server；
- Android bound service + HTTP adapter。

MVP 推荐：NanoHTTPD 或 Ktor，先保证可用。

## 7. Foreground Service

模型运行期间使用 Foreground Service：

```text
通知标题：MobileCore 本地模型服务运行中
操作：停止服务 / 查看状态
```

避免 Android 后台杀进程。

## 8. 模型目录

建议默认目录：

```text
/storage/emulated/0/Android/data/<package>/files/models
```

也支持用户通过系统文件选择器导入 GGUF。

## 9. 第一版优先级

v0.1 Android Native 只做：

```text
1. 加载一个 GGUF 模型
2. 完成一次 chat
3. 暴露 /v1/models
4. 暴露 /v1/chat/completions
5. 显示基础 metrics
```

不做：

```text
NPU 加速
模型商店
复杂 RAG
多模态
训练/微调
```

## 10. 验收标准

```text
✓ Android App 不安装 Termux 也能运行模型
✓ 能加载 GGUF
✓ MobileCode 可通过 localhost API 调用
✓ 关闭服务后内存释放
✓ OOM 时有清晰错误提示
```
