# MobileCore v0.1 Implementation Brief

## 1. 本轮实现结论

MobileCore v0.1 的实现方向已经从 Android Termux 验证路线升级为跨平台原生路线：

```text
不要依赖 Termux；
正式依赖 llama.cpp；
Android/iOS 均走原生集成；
Termux 只保留为 P0 Probe。
```

## 2. 架构调整

旧路线：

```text
Android → Termux → llama.cpp → GGUF → API
```

新路线：

```text
MobileCore API Layer
  ↓
RuntimeBackend Interface
  ↓
Android Native Llama Backend / iOS Native Llama Backend
  ↓
llama.cpp
  ↓
GGUF
```

## 3. 已新增文件

```text
src/runtime-core/runtime-contract.md
src/mobilecore-api/openai-compatible-api-v0.1.yaml
src/android-native/ANDROID_NATIVE_LLAMA_BACKEND.md
src/ios-native/IOS_NATIVE_LLAMA_BACKEND.md
examples/mobilecode-provider/mobilecore-provider-preset.json
docs/implementation/mobilecore-v0.1-implementation-brief.md
```

## 4. 产品边界

### MobileCore

负责：

```text
本地模型加载
本地模型推理
本地 OpenAI-compatible API
模型管理
手机本地 LLM benchmark
模型推荐
```

### MobileCode

负责：

```text
Agent Harness
项目管理
文件读写
Git
工具调用
任务执行
云端/本地模型调度
```

### Termux

只负责：

```text
Android P0 技术验证
开发者模式外部后端
```

不进入正式产品依赖。

## 5. v0.1 实现路线

### Step 1：固定 RuntimeBackend 接口

先在代码层定义统一接口，保证 Android/iOS 共用同一语义。

### Step 2：Android Native MVP

实现：

```text
Kotlin UI / Service
JNI bridge
llama.cpp native build
GGUF load
chat completion
localhost API
```

### Step 3：iOS Native MVP

实现：

```text
Swift UI / Runtime
Objective-C++ bridge
llama.cpp native build
GGUF load
chat completion
localhost API
```

### Step 4：MobileCode 对接

使用：

```text
examples/mobilecode-provider/mobilecore-provider-preset.json
```

让 MobileCode 把 MobileCore 当成 OpenAI-compatible provider。

## 6. 第一优先级任务

```text
1. Android native llama.cpp demo
2. iOS native llama.cpp demo
3. localhost OpenAI-compatible API
4. MobileCode provider 接入
5. benchmark quick report
```

## 7. 暂缓任务

```text
模型商店
NPU/ANE 深度优化
多模态
RAG
训练/微调
云同步
帐号系统
```

## 8. 对外定位

MobileCore 不是聊天 App，而是：

```text
Cross-platform Mobile LLM Runtime
```

中文定位：

```text
推嘛：手机本地模型运行时与跑分中心
```

## 9. 下一轮 Codex 执行建议

下一轮应优先在 MobileCore 下创建：

```text
android-app/
ios-app/
shared-spec/
```

然后先实现 Android native demo，因为 Android 调试链路更接近当前 MobileCode 生态。

建议第一个可运行目标：

```text
Android App 选择一个 GGUF 文件 → 加载 → 输入 prompt → 本地返回文本
```

第二个目标：

```text
Android App 启动 localhost API → MobileCode 调用 /v1/chat/completions
```
