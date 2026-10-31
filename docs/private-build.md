# 私人预配置构建

APK 自动命名为 agentreins-moblie-v版本号-build构建号-构建类型.apk，版本号和构建号来自 app 模块的 Gradle 配置。

本地可通过被 Git 忽略的 private-build.properties（openrouter.apiKey 属性），或 AGENTREINS_OPENROUTER_KEY 环境变量注入 OpenRouter Key。环境变量优先；不配置凭据时生成不含个人预配置的普通构建。请勿把真实 Key 写入源码、示例或提交记录。

私人构建在首次读取模型配置时初始化：

- 主模型：qwen/qwen3-vl-30b-a3b-instruct，经 OpenRouter。
- 看图和轻量模型：使用主模型。
- 语音转写：openai/whisper-1，接口 https://openrouter.ai/api/v1/audio/transcriptions。

初始化只进行一次；之后尊重用户的模型切换、Key 修改和删除。只保存加密后的运行时凭据，不自动启动录音，采集仍需用户授权。

注意：APK 本身包含构建时注入的 Key，可以被提取。此构建仅供个人使用，不应公开分发；公开发布请使用不含 Key 的普通构建，或另行部署由服务端保管凭据的方案。此处的“内置模型”是预配置云端模型，并非离线模型权重。
