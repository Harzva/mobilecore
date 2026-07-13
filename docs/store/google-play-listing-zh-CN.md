# TuiMa Google Play Listing (zh-CN)

## Identity

- App name: `推嘛 TuiMa`
- Package: `com.mobilecore.app`
- Category: Tools
- Version: `0.1.3-rc2` (`versionCode 4`)
- Target API: 35
- Ads: No
- Account required: No
- Intended audience: 13+

## Short description

用本地大模型推理，测出手机 AI 性能、响应、内存、温度与稳定性。

## Full description

TuiMa 是一款面向 AI 手机时代的本地大模型性能检测工具。

它不只测 CPU 或 GPU 峰值，而是让真实小型语言模型在手机上完成提示词预填充和逐 token 生成，再把完整体验拆成五个维度：

- 推理性能：本地模型每秒生成 token 数
- 响应能力：首 token 延迟与提示词预填充速度
- 内存效率：运行时峰值内存与设备余量
- 持续性能：多轮运行后的速度保持率
- 稳定性：完成率、温度状态和异常情况

每次检测会同时给出两层分数：

- 0–1000 标准分，便于技术比较
- 0–1,000,000 TuiMa 展示分，一眼看懂手机 AI 实力

TuiMa 提供 Quick、Standard、Stress 三档检测。Standard 使用冻结的 Qwen2.5 0.5B Q4_K_M 模型、版本化提示词和固定算法，保证同版本成绩可比较。检测前会校验模型完整性、电量、充电状态、温度、可用存储和本机推理服务；条件不合格时生成明确的无效报告，不伪造分数。

隐私优先：模型、提示词、回答和跑分报告默认留在设备上；本地 API 只监听 127.0.0.1。无需账号，无广告 SDK，无跨应用追踪。

适合想了解手机本地 AI 能力、挑选 GGUF 模型、比较不同设备持续推理表现的用户。

## Release notes

- 新增 TuiMa v2 双层跑分体系
- 新增 Quick、Standard、Stress 三档检测
- 新增电量、电池温度、Android thermal、内存与存储采集
- 新增冻结模型和提示词完整性校验
- 新增结构化报告历史与 typed failure reason
- 新增 OpenAI-compatible SSE 本地接口

## Required public URLs

- Privacy policy: `https://harzva.github.io/mobilecore/privacy.html`
- Support: `https://github.com/Harzva/mobilecore/issues`
- Source and release notes: `https://github.com/Harzva/mobilecore`

## Asset checklist

- [x] App name and descriptions
- [x] Public privacy policy source
- [x] Existing portrait product image: `docs/readme-assets/tuima-home.png`
- [ ] 512×512 Play icon export
- [ ] 1024×500 feature graphic
- [x] Two 1080×1920 Play-compatible emulator screenshots in `docs/store/assets/`
- [ ] Physical Android Standard benchmark screenshot
