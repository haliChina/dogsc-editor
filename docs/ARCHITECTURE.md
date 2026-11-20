# 架构

## 为什么不拿 OpenMinis 当 Base

OpenMinis 是聊天优先的通用 Agent 应用（GPL-3.0、PRoot/聊天数据库/媒体管线与剪辑无关且已被实测证明不耐大视频）。本项目自研薄 Base：编辑器独立，Agent 只是它的一层工具调用方。OpenMinis 将来可以通过同一套 Agent 工具定义从外部驱动本编辑器，而不是反过来。详见仓库讨论记录。

## 模块

| 模块 | 内容 | 平台 |
|---|---|---|
| `:core` | 工程模型、Op/引擎、时间线/缩放数学、FFmpeg 编译器、Agent 工具 | KMP：JVM + Android |
| `:ui` | `EditorScreen`（舞台、时间线、操作按钮） | Compose Multiplatform |
| `:androidApp` | Android 壳：导入（系统选择器 + 时长探测）、权限 | Android |
| `:desktopApp` | Windows 壳：文件选择、FFmpeg 导出、`export-cli` 无头导出 | JVM Desktop（目标 Windows） |

## 数据流

```
用户点击 / Agent 工具调用
        │  Op (JSON 可序列化)
        ▼
EditorEngine.apply() ──► Project (新 revision) ──► Op Log + 快照(撤销/重做)
        │
        ├──► 预览：各平台后端按 Project + playhead 渲染（可近似）
        └──► 导出：FfmpegCompiler 把 Project 编译成 filter_complex（权威结果）
```

## 关键约定

- 时间一律毫秒；帧吸附是 UI 辅助函数（`Timeline.snapToFrame`），不进存储。
- `Clip.durationMs` 永远是时间线（输出）时长；源消耗 = duration × speed。
- 素材只引用（URI/路径），永不复制进工程——这是从 OpenMinis 备份/附件事故里学到的：复制大视频会同时毁掉存储、备份和性能。
- 人的修改与 Agent 的修改不分层：都走 Op，都可撤销（OpenFilm 的 overrides 是另一种解法，我们选了统一 Op Log，因为剪辑操作天然离散）。
- 导出做不到的效果不许静默丢弃：编译器把它们写进 `CompileResult.notes` 返回给调用方（v1 的 3D 倾斜、阴影即如此）。

## 导出内核

`FfmpegCompiler`（单 VIDEO 轨 MVP）：
每个片段：`trim → setpts(变速) → crop 特效 → 动态 zoom crop → scale/pad 到画布内框 → 圆角 alpha 掩码(geq) → pad 到画布`，片段间间隙用背景色轨补齐，最后 `concat`；音频同片段 `atrim/atempo/volume/adelay` 后 `amix`。
动态缩放用分段 `if(between(t,...))` 表达式近似缓动（FFmpeg 表达式无三次缓动；预览用 `ZoomMath` 的精确缓动，两者在大尺度下一致、小尺度可感知差异，已知取舍）。

## Agent 工具

`AgentTools.definitions` 是工具清单，参数即对应 Op 的 JSON；`execute()` 永不抛异常，失败返回 `ok=false` 的 Receipt。内置助手（待做）与外部 MCP 客户端共用这份定义；MCP 桥只是一层薄转发，不另设工具集。
