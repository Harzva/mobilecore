# iOS Native Llama Backend v0.1

## 1. 目标

iOS 正式版 MobileCore 不能依赖 Termux，也不能依赖 iSH/a-Shell 作为正式底座。它应通过 iOS 原生 App 集成 llama.cpp。

```text
MobileCore iOS App
  ↓ Swift API Layer
  ↓ Objective-C++ Bridge
  ↓ llama.cpp native library
  ↓ GGUF model
```

## 2. iOS 路线边界

```text
iSH / a-Shell = 类似 shell 的工具，可参考，不作为正式产品依赖
llama.cpp iOS native = MobileCore iOS 正式 runtime
```

## 3. iOS 模块建议

```text
ios-app/
├── MobileCoreApp/
│   ├── Runtime/
│   │   ├── MobileCoreRuntime.swift
│   │   ├── LlamaRuntime.swift
│   │   └── RuntimeMetrics.swift
│   ├── API/
│   │   ├── LocalAPIServer.swift
│   │   └── OpenAICompatibleRouter.swift
│   ├── Models/
│   │   ├── ModelManager.swift
│   │   └── ModelMetadata.swift
│   └── Native/
│       ├── LlamaBridge.mm
│       ├── LlamaBridge.h
│       └── llama.cpp/ 或 submodule
```

## 4. Swift 层接口

```swift
protocol MobileCoreRuntime {
    func backendInfo() -> BackendInfo
    func loadModel(path: String, options: LoadOptions) async throws -> LoadResult
    func unloadModel() async
    func isModelLoaded() -> Bool
    func chat(messages: [ChatMessage], options: ChatOptions) async throws -> ChatResult
    func streamChat(messages: [ChatMessage], options: ChatOptions) -> AsyncStream<ChatToken>
    func metrics() -> RuntimeMetrics
}
```

## 5. Objective-C++ Bridge 初版

```objc
@interface LlamaBridge : NSObject
- (NSString *)backendInfo;
- (BOOL)loadModel:(NSString *)path contextLength:(NSInteger)contextLength error:(NSError **)error;
- (NSString *)chatWithMessagesJSON:(NSString *)messagesJSON optionsJSON:(NSString *)optionsJSON error:(NSError **)error;
- (void)unloadModel;
@end
```

## 6. iOS Local API Server

MobileCore iOS 同样应暴露 OpenAI-compatible local API：

```text
http://127.0.0.1:8080/v1/models
http://127.0.0.1:8080/v1/chat/completions
```

注意：iOS 对后台运行限制更强。

第一版建议：

```text
App 前台运行时提供 local API
后台长时间服务不作为 MVP 承诺
```

后续可探索：

- Background task；
- Local network permission；
- App Group；
- Shortcuts integration；
- 与 MobileCode iOS 版的 deep link / local service handoff。

## 7. 模型目录

建议使用 App sandbox：

```text
Documents/MobileCore/models
```

支持：

- Files App 导入；
- iCloud Drive 导入；
- AirDrop 导入；
- URL download 后续支持。

## 8. Metal / 加速方向

第一版不强制 Metal 加速，但架构要预留：

```text
CPU backend
Metal backend
future Core ML / ANE exploration
```

## 9. 第一版优先级

v0.1 iOS Native 只做：

```text
1. 加载 GGUF 模型
2. 完成一次本地 chat
3. 暴露 /v1/models
4. 暴露 /v1/chat/completions
5. 输出基础 metrics
```

## 10. 验收标准

```text
✓ iOS 不依赖 iSH/a-Shell
✓ 能加载 GGUF
✓ App 前台时 local API 可访问
✓ MobileCode iOS 可配置 MobileCore provider
✓ 模型关闭后释放内存
```
