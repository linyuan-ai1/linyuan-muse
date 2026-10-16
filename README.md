# linyuan-muse

「lin渊」的开源版：手机端原生 Android AI 助手（不是浏览器网页，也不需要自己部署服务器）。装到手机上就是一个常驻的 AI：能听、能说、能看屏幕、能操作手机、能联网办事、能拉一屋子专家开讨论会。

> 名字由来：早期版本叫「语音伴侣」，但功能早已远超"语音对话"，
> 那个名字会让接手项目的人一看就判定成"语音陪伴类应用"，故统一改为 **lin渊**（2026-10-06）。
> 开源发布对外用名 **linyuan-muse**；App 在手机上显示的名字保持 **lin渊**。
> 工程文件夹、工程名、代码包名（`com.linyuan.app`）、应用显示名已全部同步；
> 仅 `applicationId` 与数据库/偏好文件名因**数据兼容**保留旧值，见下方「关于内部标识」。

## 界面预览 · Screenshots

<table>
<tr>
<td><img src="screenshots/01-main-chat.jpg" width="240"></td>
<td><img src="screenshots/02-group-templates.jpg" width="240"></td>
<td><img src="screenshots/03-group-members.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/04-group-discussion.jpg" width="240"></td>
<td><img src="screenshots/05-group-list.jpg" width="240"></td>
<td><img src="screenshots/06-group-plans.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/07-docs.jpg" width="240"></td>
<td><img src="screenshots/08-settings.jpg" width="240"></td>
<td><img src="screenshots/09-image-gen.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/10-image-generating.jpg" width="240"></td>
<td><img src="screenshots/11-web-search.jpg" width="240"></td>
<td><img src="screenshots/12-history.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/13-input-panel.jpg" width="240"></td>
<td><img src="screenshots/14-context-usage.jpg" width="240"></td>
<td><img src="screenshots/15-thinking-level.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/16-image-gen-dark.jpg" width="240"></td>
<td><img src="screenshots/17-group-usage.jpg" width="240"></td>
<td></td>
</tr>
</table>

> 真机截图（OPPO 手机，含深/浅两套主题）。全部截图文件见 [`screenshots/`](screenshots/) 目录。

## 能力概览

**对话**

- 主对话：流式输出，思考 / 工具调用 / 回答三区分栏实时展示
- 群聊讨论：多角色（专家库 + 开发角色库 + 人格库）、收敛阶段、预算守卫、目标模式（不限轮数跑到收敛）
- 语音通话：按住说话 / 连续通话，AI 回复自动朗读，可打断
- 历史会话管理、备注徽章、导出方案

**能动手的 80+ 工具**（AI 自己按需调用）

- 内置浏览器：打开网页、搜索、点击、输入、读正文、取链接
- 内置地图导航：高德 SDK，找地方、算路线、导航、路况、天气
- 手机控制：手电筒、音量、亮度、勿扰、闹钟、媒体播放、应用启停
- 系统操作：无障碍模拟点击/滑动/返回/读屏、点击脚本录制回放、截图
- 通讯：联系人增删改查、打电话、发短信、读短信、读通话记录
- 文件：像浏览电脑 C 盘一样浏览/读/写/删手机文件
- 内容：图片生成、读照片、读日历/写日程、文档库
- 手机感知：位置、天气、步数、电量、使用时长、通知、最近动态
- 自我控制：AI 能操作本 App 自己的功能（删除类操作除外）
- **打字通道（自研输入法）**：`LinYuanImeService` 是一个**无界面输入法**，
  用 `InputConnection.commitText()` 逐字往任意输入框打字（打字机效果），
  连微信这种"无障碍看不到输入框"的 App 也能打，且**能打中文**（`adb shell input text` 不行）。
  还可用 `performEditorAction()` 代替用户按发送键（发没发出去会**读回核实**，不是盲报成功）。
  - 语音命令切换：说「切到你的输入法」/「切回我的输入法」→ AI 调 `app_control(ime_on/ime_off)`
  - **手动切过来会一直保持**（AI 打完也不自动切回），方便连续让 AI 输多处；想还回来下一句命令即可
  - 切回用 `InputMethodService.switchInputMethod()`，**不需要任何权限**；
    切过去若要静默需要 `WRITE_SECURE_SETTINGS`（本机 ColorOS 拿不到，会退化为弹系统选择器）
- **桌面常驻灵动岛**：`SystemIslandService` + `BrowserIslandView`，画在挖孔两侧、绕过摄像头。
  - App **在前台时隐藏**，**退到后台常驻显示**，并**可双击**切换通话模式
    （不用进 App 就能像对讲机一样跟 AI 说话，再双击挂断）
  - 瓣宽**三档**自动切换：桌面待命 `18dp`（最窄，不占地方）→ 通话待命 `34dp`
    → 干活/用户说话 `58dp`（拉长放更多文字）
  - 文字是**滚动式（右进左出）**：从右端滑进来 → 向左走 → 从左端滑出去（滚完回到静止小点）。
    速度自适应：常规 5.5 字/秒（≈中文 TTS 语速），长文/落后时 11，堆住了 30
  - 通话中的文字与**朗读音频同步**（念到哪显示到哪），流式播报每 200ms 上报进度

**按钮立体感（全 App 约定）**

- 立体感 = 按钮底图**自己画的"厚度"**：`layer-list` 两层 ——
  底面（深一档同色）+ 面孔（`android:bottom="2dp"`），下缘露出约 2dp 深色带
- 已在 `themes.xml` 用 `LinYuanButton` 主题级关掉系统阴影
  （`stateListAnimator=@null` + `elevation=0dp`），同时覆盖 XML 和 `new Button(...)`
- 改按钮外观请改这四个底图：`btn_secondary` / `btn_primary` / `btn_ghost` / `btn_danger`；
  厚度色在 `colors.xml` 的 `btn*Depth`

**设置页 UI 约定**

- 「‹ 返回」用小方形（`btn_ghost_square`，radius 6dp，高 28dp），**不要**用 `btn_ghost`（999dp 胶囊）
- 返回条用 `FrameLayout`：返回按钮 `gravity=start`、标题 `gravity=center`
  —— 标题锚整行中线，与按钮宽度无关（`LinearLayout`+weight 会跟着按钮宽度偏）

**记忆**

- SQLite 长期记忆 + 关键词/向量混合检索
- 自动提取「我叫、我喜欢、我想学、我的目标是、别忘了」等
- 两本笔记：用户记忆 / AI 自己的 self_note（完全分开）
- 文档库：导入文档分段入库，语义检索
- 一键备份 / 恢复（导出 JSON 到 Download，可直接发微信收藏）

**🤖 主动式助手（2026-10-07）**

设置页**最上面**那个入口（在所有折叠栏之上）：点开是一个跟主对话长得差不多的独立弹窗。
用户对它的定位是"跟定时任务比较像，但还是会不一样"，功能会逐步加。

- 弹窗里有：输入框 / 发送 / 🧠思考档位 / 「容量」/ 📷（相机在最右、容量在它左边）
  - 📷 **一个按钮装了三种附件**：点一下弹选项 → 📷 拍照 / 🖼 上传图片 / 📄 上传文件
    （与主对话的相机按钮同一套交互）。这一版三项都是占位（用户明确说"功能我们慢慢加"）
  - **「容量」是用户指定的名字与位置**——它只管**模型**（选服务商 → 选模型），
    存在**自己的** SharedPreferences（`assistant_panel`）

- **记忆库那两个按钮的分工**（用户 2026-10-07 要求合并后）：
  - 左「**用户**」= 用户那套 + 点开是**合并后的筛选折叠栏**
    （全部 / 只看置顶 / 用户画像 / 长期记忆 / 它的记忆 / 已归档）
  - 右「**助手记忆**」= 助手那套的**入口**

- **`打字通道`（`LinYuanImeService`）是干什么的**：微信/QQ 屏蔽无障碍读取，
  它们的控件树是空的、抓不到输入框，AI 用无障碍填不进字。打字通道 = App 自带的输入法，
  AI 打字时临时切过去、打完切回。它放在「基础开关」是因为微信自动回复和 AI 代操作都依赖它。

**语音**

- ASR：Android 系统识别 / 火山引擎豆包
- TTS：腾讯云 → 阿里云池 → 豆包，多通道自动兜底
- 端侧声纹：sherpa-onnx + CAM++，通话时先验证是不是主人，过滤电视/他人说话
- 语音模型额度池：轮换 + 每月 1 号自动重置

**任务**

- 定时任务（AlarmManager，支持精确闹钟）
- 编辑、查看、删除，跑完自动以通知汇报
- **微信自动回复**（设置 → 定时任务 → 选「📱 微信自动回复」）

**微信自动回复（2026-10-07）**

在设定的时段内，微信来消息就自动进微信帮你回复。入口在**设置 → 定时任务**（点进去直接就是
「📱 微信自动回复 / ⏰ 定时任务」两个创建入口 + 已有任务列表，没有多余的中间层）。

- **时段**：支持「每天」或「每周几」，起止时间可跨午夜（如周一、周三 23:00–03:00），
  表单里有实时预览确认自己设对了没
- **总开关**在时段管理页顶部；单条时段还能单独暂停

- 记忆策略：**带全部长期记忆**（用户画像 / 长期笔记 / 它自己的笔记 / 实体记忆 / 知识库片段），
  **不带短期记忆**
- 专属提示词：只做"回微信消息"这一件事；拿不准的、敏感的（钱/承诺/隐私/吵架）**不回**；
  对方让代买/代查时**不许代替用户承诺**；少回一条没关系，回错一条很严重

## 生成 APK

需要 JDK 17（Android Studio 自带的 JBR 即可）和 Gradle 8.7。设置好环境后执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = '<你的 Android SDK 路径>'
& "<Gradle 8.7 路径>\bin\gradle.bat" assembleDebug
```

APK 输出在：

`app/build/outputs/apk/debug/app-debug.apk`

安装到已连接的手机：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

**前置依赖**：仓库自带了 `sherpa-onnx-1.13.6-slim.aar`（Apache-2.0，随仓库分发）。
高德地图 SDK（`amap-navi-map-loc-search-11.3.100.jar`，约 106MB）是**专有 SDK，版本协议不允许随仓库分发**，
构建前请自行到[高德开放平台](https://lbs.amap.com/)下载并放入 `app/libs/`（文件名与版本号见 `app/build.gradle`）。
高德 Key 在 App「设置」里运行时配置，不接触源码。

## 真实 AI 设置

打开 App 的「设置」：

- 阿里云百炼：地址默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型可以填 `qwen-plus`
- 火山方舟：地址默认 `https://ark.cn-beijing.volces.com/api/v3`，模型名填你在方舟控制台创建的接入点 ID
- OpenAI 兼容：填写对应服务的兼容地址、模型名和 API Key

API Key 只保存到手机 App 的私有设置里（`SecretBox` 加密），不写入源码。**公开版已清空内置的搜索/生图密钥池**，这两项功能请在「设置 → 搜索」「设置 → 图片生成」里填入你自己的服务地址和 Key（每行一条 `地址|Key`）。不填也能用「本地演示模式」体验完整交互。

## 关于内部标识（哪些改了、哪些**故意**没改）

改名覆盖：工程文件夹（`lin渊-android`）、工程名（`settings.gradle`，开源副本用 ASCII `linyuan`）、**代码包名 `com.linyuan.app`**、应用显示名、无障碍/通知服务名、全部用户可见文案、README 指引文字。

以下标识保持历史命名 `voicecompanion`，因为改动会造成**真实损失**（不只是"看起来不一致"）：

| 标识 | 位置 | 改了的后果 |
|---|---|---|
| `applicationId com.voicecompanion.app` | `build.gradle` | 变成**另一个 App**：老用户升级＝数据全丢，运行时权限、无障碍、通知使用权要全部重新授予 |
| 数据库名 `voice_companion.db` | `AppDatabase` | 找不到老库，记忆/对话/文档全没 |
| SharedPreferences `voice_companion` | 多处 | 所有设置（含 API Key）丢失 |

> 代码包名（namespace）与 applicationId 是两件事：前者只影响源码组织，可以安全改；后者是应用在系统里的身份，改了就是新应用。本项目只改了前者。

新生成的图片改存新目录 `Pictures/lin渊/`；旧目录 `Pictures/VoiceCompanion/` 的图仍在，两者都不删。

## 当前能力边界

当前是「手机端近实时语音对话」：Android 语音识别 → AI 对话 → 手机 TTS 合成。已经能作为可安装 App 日常使用，但还不是豆包那种 WebRTC 全双工实时音频流。

下一阶段如果追求豆包/ChatGPT 手机端一样的低延迟打断、边听边答、连续音频流，需要接入具体厂商的实时语音 SDK 或 WebSocket/WebRTC 接口；这一部分必须根据最终选择的国内模型服务来定，不能把 API Key 写死在 App 里。

## 开发约定

- **纯框架工程**：`android.useAndroidX=false`，不能引 androidx 依赖（Markdown 用 commonmark 自己 span 化，FileProvider 是自研的）。
- **依赖是本地 jar/aar**：高德 SDK、sherpa-onnx，不走 Maven（高德 jar 需按上文自行下载）。
- **没有单元测试**：验证方式 = 构建成功 + 真机实测。
- **开发记录不进仓库**：内部的开发日志与问题手册属于作者私有文档，不随仓库分发；代码改动留痕看 commit 历史。

## 素材来源与致谢

App 内置的群聊角色提示词库（开发角色 / 名人思维 / 工程师人格等）**大部分收集自 GitHub 开源提示词项目，或在其基础上经 AI 蒸馏改编，并非本项目原创**；已知来源与其许可证、"无法追溯来源"部分的诚实说明，见 **[CREDITS.md](CREDITS.md)**。如你是原作者、希望补充署名或要求移除相关内容，欢迎开 Issue 联系我们。

## 许可证

[GNU AGPL-3.0](LICENSE)（GNU Affero 通用公共许可证第 3 版）。

- 使用、修改、分发都自由，但**衍生作品必须同样以 AGPL-3.0 开源**，不允许改成闭源；
- 与 GPL 的关键区别（第 13 条）：**就算你只把它部署成网络服务给别人用、一个字节都不分发，也必须向使用者提供完整源码**；
- 商用并没有被禁止，但商用方同样受"必须开源"的约束。

> 随仓库分发的第三方组件许可证：sherpa-onnx（Apache-2.0）、commonmark（BSD-2-Clause）、kotlin-stdlib（Apache-2.0）、端侧声纹模型 voiceprint.onnx（3D-Speaker CAM++，经 sherpa-onnx 分发，Apache-2.0；详见 [CREDITS.md](CREDITS.md)）。
> 高德地图 SDK 为专有软件，不随仓库分发（需自行下载，另受高德开放平台协议约束）。
