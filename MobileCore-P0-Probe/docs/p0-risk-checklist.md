# P0 Risk Checklist

## 1. 环境风险

- [ ] Termux 版本过旧。
- [ ] Termux 包源不可用。
- [ ] `llama-cpp` 包不存在或版本过旧。
- [ ] 手机 CPU 架构不兼容。
- [ ] 存储空间不足。
- [ ] 网络无法下载源码或模型。

处理建议：

```text
优先更新 Termux；
优先测试小模型；
源码编译作为备选；
必要时改走 Android NDK native route。
```

## 2. 模型风险

- [ ] 模型文件过大。
- [ ] 模型格式不是 GGUF。
- [ ] 模型量化等级过高导致内存不足。
- [ ] 模型上下文设置过大。
- [ ] 模型来源不可信。

处理建议：

```text
P0 只用 GGUF；
优先 Q4；
先测 0.5B/1B/1.5B；
再测 3B/7B；
记录模型来源、大小、量化等级。
```

## 3. 运行风险

- [ ] llama-server 启动失败。
- [ ] 加载模型 OOM。
- [ ] 推理速度过慢。
- [ ] 手机发热严重。
- [ ] Android 杀后台。
- [ ] 长时间运行耗电过快。

处理建议：

```text
降低 context；
降低 max_tokens；
换小模型；
保持前台运行；
记录失败日志。
```

## 4. API 风险

- [ ] `/v1/models` 不兼容。
- [ ] `/v1/chat/completions` 不兼容。
- [ ] stream 格式异常。
- [ ] MobileCode WebView 调用 localhost 失败。
- [ ] cleartext traffic 被 Android 拦截。

处理建议：

```text
先 curl 验证；
再浏览器验证；
最后 MobileCode 验证；
必要时加一个 MobileCore API proxy。
```

## 5. 安全风险

- [ ] 开放 0.0.0.0 导致局域网可访问。
- [ ] 没有 API token。
- [ ] 请求日志泄露隐私。
- [ ] 模型文件来源不明。

处理建议：

```text
默认只监听 127.0.0.1；
P0 阶段 token 可简化；
正式版必须加 token 和授权；
LAN 模式必须二次确认。
```

## 6. 产品风险

- [ ] 误把 MobileCore 做成聊天 App。
- [ ] 与 MobileCode 功能边界混淆。
- [ ] 一开始就追求完整 Android App，导致 P0 变慢。
- [ ] 过早支持太多后端。

处理建议：

```text
MobileCore = Runtime/API/Benchmark；
MobileCode = Agent/Harness/IDE；
P0 只打通链路；
先 llama.cpp/GGUF，后扩展其他后端。
```

## 7. P0 Go / No-Go 判定

### Go

满足：

```text
至少一个模型可运行；
API 可访问；
MobileCode 可调用；
有基础性能数据。
```

### Conditional Go

满足：

```text
模型能运行但速度慢；
API 能访问但 stream 有问题；
MobileCode 需要小改才能接入。
```

### No-Go

出现：

```text
Termux/llama.cpp 完全无法运行；
Android localhost 无法被 MobileCode 调用；
所有模型均 OOM；
无法获得稳定推理结果。
```

No-Go 后改走：

```text
Android NDK + JNI llama.cpp
```
