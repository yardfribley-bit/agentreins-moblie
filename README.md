# X 小助手

简体中文 | [English](README.en.md)

X 小助手（AgentReins Mobile）用一句话让 AI 帮你操作手机，比如比价、发帖、查资料。

官网：<https://www.agentreins.info>

## 截图

还没有放进仓库的真实截图。下面三处先留着，补上之后替换「截图待补」。

**首次引导**

截图待补

**比价结果**

截图待补

**知识库读写记录**

截图待补

## 能做什么

- 一句话比价。在购物 App 里查同一件商品，只看价格，一家一家来。要查哪些 App 可以在设置里改，对话里也可以只点名其中几家。
- 用你自己的 Obsidian 文件夹当知识库。任务开始前会读相关笔记，办成后再写回去，对话里能看到读了哪几篇、写了哪几篇。
- 配好后台屏之后，助手在一块不占用你屏幕的副屏上点 App。你的屏幕可以照常用。
- 可以加入一个 Hub 会话，把任务进度写到那里，也可以执行这个会话里负责人下的指令。
- 出声默认关闭。你允许之后才会说话；说话时看得到提示，可以一键停止。
- 也可以让它看网页、整理你给的文件，或按你的话发帖、查资料。

已经认识的 App、网站和做法（这份清单由构建生成，不要手改）：

<!-- generated:knowledge:start -->
<!-- 这一段由 ./gradlew updateReadme 生成，不要手改 -->
| 类别 | 内置 |
|---|---|
| **App** | Android 设置、百家号、哔哩哔哩、BOSS直聘、Chrome、得到、抖音、飞书、Gmail、Grok Bot、欢乐斗地主、即刻、快手、领英、QQ邮箱、Telegram、今日头条、Uber、Uber Eats、微信、公众号助手、微博、WhatsApp、X、闲鱼、小红书、知乎 |
| **网站** | Amazon、哔哩哔哩、Google Calendar、豆瓣、抖音、Instagram、Gmail、Google Maps、Telegram、百度贴吧、微博、搜狗微信、WhatsApp、小红书、小宇宙、YouTube、知乎、BOSS直聘 |
| **通用做法**（技能） | 查找并安装安卓应用、把内容整理成文字、处理 PDF 文档、查清一件事、直播连麦出声、消息与邮件、比同一件商品的价格 |
<!-- generated:knowledge:end -->

## 三步上手

全程在手机上完成，不需要电脑，也不需要在电脑上运行 adb。

1. 下载安装包。到 [Releases](https://github.com/yardfribley-bit/agentreins-moblie/releases) 取 APK。如果那里还没有包，用 [Actions](https://github.com/yardfribley-bit/agentreins-moblie/actions) 里成功的那次 Android build，下载它附上的 debug APK。仓库里不带填好的模型 Key。
2. 打开 App，按引导做。先配模型：推荐 DeepSeek，在手机上打开 [申请 API Key](https://platform.deepseek.com/api_keys)，把 Key 贴进去。保存时会试一次，通过才当成主模型。然后打开 X 小助手的无障碍。知识库可以在这一步选一个文件夹，也可以先跳过，页面会说明跳过之后怎样。
3. 试一句。引导最后有一条只用模型、不操作手机的示例；之后也可以自己说，比如「比一下牛奶的价格」。

后台副屏是可选的增强。聊天、看网页、整理文件不用它。要比价或让助手去点 App，才需要在手机上打开开发者选项，配对一次无线调试。配对在手机里完成，引导会一步步带你做。只开无障碍建不出这块副屏。

也可以换其他服务商：

<!-- generated:providers:start -->
<!-- 这一段由 ./gradlew updateReadme 生成，不要手改 -->
内置 DeepSeek、通义千问、Kimi、智谱、硅基流动、火山方舟、MiniMax、阶跃星辰、腾讯混元、百度千帆、OpenRouter、OpenCode Go、Anthropic、Gemini、OpenAI、xAI、Mistral、Groq、Ollama、LM Studio 等服务商预设；其他服务商选「自定义」，OpenAI 兼容、Anthropic、Gemini 接口都能接。
<!-- generated:providers:end -->

## 安全和透明

- 比价只读。不加购物车，不下单，不领券，不登录。遇到登录页就记下原因，换下一家。
- 付钱的操作会先在手机上问你。Hub 里负责人的指令也一样：付款、转账、删除、给别人发消息、输入密码，都要你在这台手机上确认。
- 出声默认关闭。说话时界面上有提示，可以一键停止。
- 每次任务读写了哪些知识库笔记，对话里看得到。回答下面也可以打开这次用过的来源。

## 常见问题

**为什么需要无线调试？**

安卓只允许通过无线调试做出那块不占你屏幕的副屏。无障碍用来看见和点。两件事都在手机上做，不需要电脑。聊天、网页和文件不用这一步。配对码大约一分钟过期，过期就重新取一个。

**没有模型 Key 怎么办？**

到 DeepSeek 申请一把，贴进引导里的模型页。保存失败时，先核对是不是完整复制，或换上面列出的其他服务商再试一次。没有可用的模型时，需要模型的任务开不了。

**比价和多屏对手机有什么要求？**

比价用一块副屏，按顺序查，不会同时打开好几家。手机上要装着你要查的那些 App；没装的会记成没查到，不会编一个价格。安装需要 Android 11 或更新的系统。副屏还要完成上面的无障碍和无线调试配对。同时开多块屏不是现在的比价做法。

**知识库要装 Obsidian 插件吗？**

不用。在 App 里选一个文件夹，或新建一个。笔记写在这个文件夹里的 AgentReins 子文件夹，不覆盖你原来的页。你愿意的话，可以用 Obsidian 打开同一个文件夹来看。

## 开发者

需要 JDK 17 和 Android SDK。在仓库根目录执行：

```sh
./gradlew :app:assembleDebug
```

编出来的 debug APK 在 app 模块的 build 输出里，文件名里带版本号。推到 main，或向 main 提 Pull Request 时，GitHub Actions 的 Android build 会编出同样的 debug 包，挂在那次运行的产物里。

主要目录：app 是手机上的界面，core 是任务和工具，docs 是更长的说明，.github/workflows 是上面的构建。

改动请向 [这个仓库](https://github.com/yardfribley-bit/agentreins-moblie) 提 Pull Request。

基于开源项目修改，Apache-2.0。
