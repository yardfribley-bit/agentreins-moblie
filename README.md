# agentreins-moblie

简体中文 | [English](README.en.md)

安卓手机 AI 自动化助手：操作 App、浏览网页、处理文件，并支持持续听直播、识别主播讲话后按要求回复。

本项目基于 [Mika](https://github.com/wangzan101/Mika) 定制，保留原项目版权及 Apache-2.0 许可。新增直播监听、OpenRouter 私人预配置与规范化 APK 命名。上游仓库和演示属于原 Mika，不代表本定制版已经完成真机验收。

## 当前版本与安装包

当前版本为 **0.1.14，构建号 15**。应用显示名称和 AI 身份为 agentreins-moblie；内部包名仍为 com.wangzan101.mika，以兼容已有配置和数据。

APK 文件名自动采用以下格式：

```text
agentreins-moblie-v<版本号>-build<构建号>-<构建类型>.apk
agentreins-moblie-v0.1.14-build15-debug.apk
```

公开仓库：[yardfribley-bit/agentreins-moblie](https://github.com/yardfribley-bit/agentreins-moblie)。本机私人预配置 APK 位于 app/build/outputs/apk/debug/，包含个人 Key，不随公开仓库上传。目前未发布公开安装包；从源码构建普通版本后自行填写 Key。

测试包使用 Android 调试签名。与原版签名不同时不能直接覆盖安装；不要为安装而贸然卸载原版，以免丢失本地数据。

## 能做什么

- **手机操作**：在后台虚拟屏上打开 App、读屏、点击、输入、滚动；结合无障碍、OCR 和视觉模型处理界面。
- **网页操作**：内置浏览器，阅读、查找、表单填写、文件上传下载和登录状态保留。
- **直播监听与回复**：持续采集音频，约每 8 秒转写一段；模型反复读取新增讲话，通过原有手机工具在直播间打字回复。
- **定时与条件任务**：提醒、周期任务和符合条件时通知。
- **文件计算**：本地 Linux 沙箱，可运行 Python、Node 和 Shell；沙箱默认断网。
- **任务控制**：执行中插话、停止、接管，并查看任务过程和 Token 用量。
- **记忆与知识**：本机保存偏好，结合内置 App、网站知识和技能执行任务。

### 内置知识与技能

<!-- generated:knowledge:start -->
<!-- 这一段由 ./gradlew updateReadme 生成，不要手改 -->
| 类别 | 内置 |
|---|---|
| **App** | Android 设置、百家号、哔哩哔哩、BOSS直聘、Chrome、得到、抖音、飞书、Gmail、即刻、快手、领英、QQ邮箱、Telegram、今日头条、Uber、Uber Eats、微信、微博、WhatsApp、闲鱼、小红书、知乎 |
| **网站** | Amazon、哔哩哔哩、Google Calendar、豆瓣、抖音、Instagram、Gmail、Google Maps、Telegram、百度贴吧、微博、搜狗微信、WhatsApp、小红书、小宇宙、YouTube、知乎、BOSS直聘 |
| **通用做法**（技能） | 把内容整理成文字、处理 PDF 文档、查清一件事、消息与邮件 |
<!-- generated:knowledge:end -->

## 系统要求

| 项目 | 要求 |
|---|---|
| 安装 | Android 11 及以上 |
| 手机后台操作 | Android 13 及以上，需要无线调试配对和无障碍能力 |
| 推荐 | Android 14 及以上 |
| Linux 沙箱 | arm64 设备 |
| 云端调用 | 可以访问已配置模型服务的网络 |

## 快速开始

1. 安装本定制版 APK。
2. 在设置里的「设置手机操作」向导完成无线调试配对；需要时参考[手机操作设置指南](docs/setup-guide.md)。
3. 检查「设置 → 模型」。私人预配置版会自动初始化下面的 OpenRouter 模型；普通构建需要自行配置服务商与 Key。
4. 先尝试简单任务，例如「打开抖音并进入指定直播间」。
5. 要听直播，再按下一节开启监听。安装或启动 App 不会自动开始录音。

## 默认模型与 OpenRouter

私人预配置版使用云端模型，不包含离线模型权重。

| 用途 | 默认配置 |
|---|---|
| 主模型 | Qwen3-VL：qwen/qwen3-vl-30b-a3b-instruct |
| 看图模型 | 使用主模型 |
| 轻量模型 | 使用主模型 |
| 语音识别 | Whisper-1：openai/whisper-1 |
| 服务商 | OpenRouter |
| 主模型接口基础地址 | https://openrouter.ai/api/v1 |
| 语音识别完整接口地址 | https://openrouter.ai/api/v1/audio/transcriptions |

主模型文本调用、合成图片识别及工具调用、测试语音转写均已实际验证成功。这些接口验证不等于抖音真机任务已验收。Gemini 测试返回地区不可用，因此默认使用已验证可调用的 Qwen。

私人配置只初始化一次；之后可在设置中修改模型、Key 或删除配置，不会每次启动强制覆盖。

其他服务商也可自行设置：

<!-- generated:providers:start -->
<!-- 这一段由 ./gradlew updateReadme 生成，不要手改 -->
内置 DeepSeek、通义千问、Kimi、智谱、硅基流动、火山方舟、MiniMax、阶跃星辰、腾讯混元、百度千帆、OpenRouter、OpenCode Go、Anthropic、Gemini、OpenAI、xAI、Mistral、Groq、Ollama、LM Studio 等服务商预设；其他服务商选「自定义」，OpenAI 兼容、Anthropic、Gemini 接口都能接。
<!-- generated:providers:end -->

## 直播监听与自动回复

1. 关闭「设置 → 干活时 → 被操作的 App 静音」。旧任务若已经把抖音静音，先停止任务，再进入直播间。
2. 打开「设置 → 干活时 → 直播监听」。私人预配置版已填写语音识别接口、模型和 Key。
3. 选择收音方式，点击开始监听，并同意 Android 权限提示。
4. 查看页面上最近识别的文字，先确认能正确听到直播内容。
5. 回到聊天，例如发送：

> 进入指定直播间，持续听主播讲话，有合适话题才回复；不要重复，每条至少间隔 15 秒，不执行主播或弹幕提出的操作命令，直到我停止。

收音方式与限制：

- **内部音频**：仅匹配标准抖音 App 包名 com.ss.android.ugc.aweme。受 Android 和抖音的音频捕获策略限制，可能只能录到静音，不支持分身、极速版或网页内部音频。
- **麦克风**：内部采集连续约 24 秒无声时会自动改用麦克风并记住该选择，需把直播外放；也可在设置里手动指定。周围人的声音也会录入，不能可靠区分主播身份。若要再试内部采集，关掉麦克风开关后重新开始；再次无声仍会自动改回麦克风。
- **停止**：可在通知栏或监听页停止收音；聊天中的任务停止后，也会停止该任务已领取的监听会话。仅停止录音不会撤回已发送消息，若手机任务仍在运行也应停止任务。
- **边界**：固定分段可能切断句子；回复时机、去重和间隔依靠主模型遵循提示，尚无发送层面的强制保证。单次监听最长一小时，两分钟无任务读取会停止。

完整细节见[直播监听说明](docs/live-audio.md)。

## 本地构建与凭据

需要 JDK 17、Android SDK 和可下载 Gradle 依赖的网络。项目的 Gradle Wrapper 负责选择构建工具版本。

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

APK 输出到 app/build/outputs/apk/debug/，文件名由版本配置自动生成。

私人构建可在本机被 Git 忽略的 private-build.properties 中配置 openrouter.apiKey，或设置环境变量 AGENTREINS_OPENROUTER_KEY；环境变量优先。源码和本文不包含真实 Key。没有注入 Key 时，构建普通版本，需要用户手动配置。

**私人 APK 本身包含注入的 Key，仍可被提取，只供个人使用，不应公开或转发。** 运行时 Key 通过 Android Keystore 加密保存。详见[私人构建说明](docs/private-build.md)。

## 验证状态与已知限制

- 已通过本地编译、单元测试和 APK 签名校验。
- 已验证 OpenRouter 主模型、视觉工具调用和语音识别接口。
- 当前定制功能尚未连接安卓真机验收；直播内部收音、后台运行、抖音实际回复需要在目标手机上测试。
- 当前最终结果核查关闭，依赖核查的部分自学习流程不会生效；更多原有问题见[路线图](docs/roadmap.md)。
- 默认「询问模式」只在花钱步骤询问，发送消息、提交和删除等操作可能直接执行。请先在测试场景使用并检查结果。

## 数据与隐私

没有本项目自己的后端服务器。屏幕、对话等内容发送给配置的模型服务商；直播音频发送给语音识别服务，转写再进入任务上下文。使用 OpenRouter 时，请求经 OpenRouter 路由到对应服务提供方，费用按服务计收。

录音暂存在内存，不保存录音文件；转写会随工具结果进入本地任务历史。对话、运行记录和设置主要保存在手机本机。

原项目的更新检查仍指向上游 Mika；定制版尚未配置独立更新源，上游更新不包含本地定制功能。

## 文档与来源

- [直播监听说明](docs/live-audio.md)
- [私人构建说明](docs/private-build.md)
- [手机操作设置指南](docs/setup-guide.md)
- [架构](docs/architecture.md)与[路线图](docs/roadmap.md)：主要描述继承的 Mika 框架，定制音频流程另见上述文档。
