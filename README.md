# DogSC Editor（社区版 · Android / Windows）

基于 [laogou717/dogsc](https://github.com/laogou717/dogsc)（MIT，作者：神烦老狗）的演示型视频剪辑器社区移植。
原版是 macOS Swift 应用，本项目是独立重写：**不做录屏**（手机/系统自带录屏足够），只做它最有价值的剪辑部分——时间线剪辑、运镜缩放、演示画布（留白/圆角/阴影/背景），并把同一套剪辑操作开放给 AI Agent。

## 平台与架构

- **共享内核（Kotlin Multiplatform）**：工程模型、Op Log、时间线数学、特效参数、FFmpeg graph 编译器、Agent 工具定义——Android 与 Windows 共用一份。
- **UI（Compose Multiplatform）**：同一个 `EditorScreen` 在两端运行。
- **Android**：原生应用（`androidApp`），预览/导出后端规划为 Media3（导出尚未接通，界面有明示）。
- **Windows**：Compose Desktop（`desktopApp`），可打包 MSI/EXE；导出走 FFmpeg graph，已可端到端出片。
- 不支持 macOS / Linux（桌面端是 JVM，理论可跑，但不在目标内，不做适配承诺）。

设计细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 核心设计

1. **一切剪辑都是 Op**：分割、裁剪、移动、变速、加缩放、改画布……全部是可序列化的 `Op`，UI 按钮和 Agent 工具走同一条 `EditorEngine.apply()`，每步可撤销，工程文件就是 JSON。
2. **特效只存参数**：缩放倍率/焦点/缓动、圆角半径、留白、倾斜角——工程里只有声明式参数，没有任何平台对象，所以同一工程能在任何后端渲染。
3. **导出是编译**：工程 JSON 被编译成一条 FFmpeg `filter_complex`，两端喂同一个 graph，出片一致。预览可以是近似，导出以 graph 为准。
4. **Agent 是一等公民**：`AgentTools` 把每个 Op 暴露为类型化工具，每次调用返回 Receipt（工具名、revision 前后、可否撤销），抄的是 Kinocut/OpenChatCut 验证过的打法。

## 构建

需要 JDK 21、Android SDK 35/36（仅 Android 端）。

```sh
# 内核测试（含一个真实 FFmpeg 渲染的端到端测试，无 ffmpeg 时自动跳过）
./gradlew :core:jvmTest

# Android 调试包
./gradlew :androidApp:assembleDebug

# Windows 桌面（在 Windows 上打包 MSI/EXE）
./gradlew :desktopApp:packageMsi

# 无头导出（桌面 JVM）：工程 JSON -> MP4
./gradlew :desktopApp:run --args="export-cli project.json out.mp4"
```

## 现状（v0.1 脚手架）

- [x] 内核：Project/Track/Clip/Effect 模型、Op 全集（分割/裁剪/移动/变速/音量/缩放段/演示画布/画布设置）、撤销重做、时间线吸附/帧吸附、缩放缓动数学
- [x] FFmpeg 编译器：多片段拼接、间隙补背景、变速、裁切、动态缩放（分段表达式）、留白+圆角（alpha 掩码）、音轨混合
- [x] Agent 工具层 + Receipt
- [x] 两端编辑器 UI 骨架：导入、示意预览（缩放/画布实时反映）、时间线、分割/加缩放/演示画布/撤销重做
- [ ] Android：真实视频预览（Media3 Player 接入示意舞台）、Media3 导出后端、导入时长/分辨率探测完善
- [ ] Windows：ffprobe 探测时长、真实视频预览（mpv 或 FFmpeg 抽帧）
- [ ] 导出 v2：3D 倾斜与阴影（当前仅预览，导出时会在 notes 里明示，不会静默丢弃）、atempo 超出 0.5–2 需链式
- [ ] 字幕轨、画中画、背景音乐 ducking（二期）
- [ ] 内置 AI 助手面板（薄 LLM 客户端，工具集只开时间线 Op）

## 许可

MIT。原项目 DogSC © 神烦老狗（laogou717），本项目为社区独立实现，保留其署名。
