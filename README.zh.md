# DSHN — DeepSeek Harness Native (原生安卓客户端)

[English](README.md) | 中文说明

DSHN 是基于 Android 原生 Jetpack Compose (Material 3)、Rust 原生性能核心与 [`bxroot`](https://github.com/qiannianhuanxiang/bxroot)（`arm64-v8a`）构建的 [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/dsh) 原生安卓客户端。

它在 **功能集成不修改上游 DSH 源码** 的前提下（仅在容器侧保留 1 处可自动跳过的 MIT `profile-resolution` 启动加速热优化），开箱内置离线运行环境（Ubuntu 24.04.5 + Node.js 24.22.3 + ACP Slim 裁剪版 DSH `0.2.2-rc.5`），通过标准 **ACP 协议**、只增式 `--patch` Cordis 桥接插件与 **MCP 服务器**，在手机本地提供完整的 Agent 编码与对话体验、插件管理（含本地插件包导入）、上下文与缓存命中率透视、真实 PTY 终端，以及默认关闭的可选设备桥接与多分辨率后台虚拟屏能力。

---

## 什么是 `perf`？（`v0.4.0-perf` / `feat/rust-perf-engine`）

版本号和分支名里的 **`perf`** 是 **Performance（性能优化版）** 的缩写（不是 Linux 的 `perf` 性能分析命令），代表 DSHN 面向移动端做的一整套**全链路提速、瘦身与降耗升级**：

1. **热点下沉到 Rust 原生代码**：把原先在 JVM / Node 里最耗时的 4 件事——离线环境解压、容器冷启动前的极速直连对话、后台虚拟屏帧编码、跨容器 MCP 中继——全部用 AArch64 Rust 重写。
2. **R8 极致裁剪**：启用 `stagingRelease` / `release` R8 代码优化与资源收缩，DEX 从 13 个 `54.71 MB` 降到 **1 个 `3.02 MB`（-94.5%）**，APK 从 `180 MB` 降至 **`120 MB`**（去掉 `115 MB` 离线环境包后 App 本体仅约 **`5.2 MB`**）。
3. **冷启动与运行时加速**：主线程去阻塞 I/O、Node 24 V8 字节码编译缓存（`NODE_COMPILE_CACHE`）、MIT 许可的 DSH Sugar `profile-resolution` 引导加速、按需挂载 MCP 中继、40 ms 流式聚合刷新——在 Android 17（SM8850）实机上冷启动进入 **~95 ms**、常驻内存下降 **30%**。
4. **“秒开即聊”且上下文无损衔接**：容器冷启动期间（约 2–3 秒），Rust 原生 `rustls` 客户端在约 **0.25 s** 内即可提供极速直连对话；容器就绪后自动通过结构化上下文交接（`FastChatHandoff`）把已聊轮次无损带入完整 DSH ACP 会话并持久化到 DSH 自身会话日志，重开历史自动还原为独立气泡。

---

## 整体架构：双层混合运行时

DSHN 采用 **「Android Bionic 原生层 + Ubuntu glibc 用户态容器 + 静态 musl 桥接」** 三段协同、**双进程隔离**（`:app` UI 进程与 `:runtime` 容器守护进程）以及 **11 个 Gradle 模块**（`:app` + 5 个 `:core:*` + 5 个 `:feature:*`）的清晰分层架构：

```text
┌─────────────────────── :app 进程 (Android Bionic 原生环境) ───────────────────────┐
│  Kotlin / Jetpack Compose UI (对话 / 插件管理 / 上下文透视 / 终端 / 设备桥 / 设置)  │
│  ├─ 11 模块解耦：:core:{data,security,runtime,bridge,ui} + :feature:{5 个页面模块}  │
│  ├─ Rust JNI 核心 libdshn_core.so (Bionic, 16KB 对齐, 2.16 MB, 零翻译零开销)       │
│  │    ├─ extractor.rs : APK 零拷贝切片流式解压 (.tar.gz/.tar.zst) + 硬件 SHA-256   │
│  │    ├─ fast_chat.rs : rustls + ring 极速直连 SSE 对话 (~0.25 s 可用)             │
│  │    └─ frame.rs     : RGBA DirectByteBuffer → FNV-1a 脏帧跳过 → SIMD JPEG        │
│  ├─ DeviceBridgeMcpServer (私有 UDS: files/bridge/device-mcp.sock, 0600)          │
│  └─ VirtualDisplayController (经已授权 Root 或无线 ADB 驱动独立后台虚拟屏)          │
└─────────────────────────────┬──────────────────────────┬──────────────────────────┘
                              │ AIDL + Unix Socket       │ su / ADB shell,v2,raw
                              ▼                          ▼
┌─────────── :runtime 进程 (bxroot + Ubuntu 24.04 glibc) ──────────┐ ┌─ app_process ─┐
│  前台服务 (specialUse) 隔离守护，容器崩溃不影响主界面                │ │ 后台虚拟屏代理  │
│  ├─ CPU 指令：原生 ARM64，零指令集翻译                             │ │ DisplayManager│
│  ├─ 路径兼容：bxroot LD_PRELOAD 轻量拦截（远轻于 proot ptrace）     │ │ ImageReader   │
│  ├─ Node 24 + DSH 0.2.2-rc.5 (`dsh --profile acp --patch ...`)   │ │ UiAutomation  │
│  ├─ dshn-session-bridge.mjs (--patch 注入，不改 DSH 源码)：        │ │ InputManager  │
│  │    会话历史 / 上下文与缓存命中率透视 / 插件管理 / 提问与斜杠命令   │ └───────────────┘
│  └─ /opt/dshn/dshn-mcp-relay (393 KB 静态 musl Rust，不依赖 libc)  │
│       └─ 取代额外 Node 进程，直连宿主机 device-mcp.sock             │
└──────────────────────────────────────────────────────────────────┘
```

### 关于运行环境与开销的直白说明

| 组件 | 运行环境 | 是否有翻译 / 额外开销？ |
|---|---|---|
| **App UI、Rust 核心（解压 / 极速直连 / 图像编码）、虚拟屏代理** | Android 原生 **Bionic** | **零翻译、零额外开销**，直接调用原生指令与系统调用 |
| **DSH 主体、Node.js 24、`node-pty`、Ubuntu CLI 工具** | `bxroot` + Ubuntu 24.04 **glibc** | **CPU 指令零翻译**（全原生 `arm64-v8a`）；仅文件/路径访问经 `bxroot` 的 `LD_PRELOAD` 用户态轻度改写，比 `proot`（`ptrace`）快一个数量级，换取完整 Linux/Node 生态且无需魔改 DSH |
| **设备桥中继 `dshn-mcp-relay`** | 静态链接 **musl**（零 libc 依赖） | **零额外 V8 进程**，直接连接宿主机 UDS 路径 |

---

## 核心功能

- **原生对话与交互体验**：
  - **清爽模型与思考强度选择器**：合并为单一胶囊下拉菜单，去掉冗长英文简介，支持一键切换模型与中文化思考强度档位。
  - **上下文百分比交互透视弹窗**：点击输入框旁的百分比圆环，实时弹出当前会话的上下文占用与三段占比条（系统提示词 / 工具 / 对话消息）、**Prompt 缓存命中率**（按 DSH `sessionProjections` 精确口径计算，含缓存读取/写入/未命中/输出与最近一轮命中率）、可展开复制的 **System Prompt** 分段与动态运行时上下文、已挂载 **Tools**（标注内置 / `MCP` / 按需加载）以及已发现的 **Skills**。
  - **多会话持久化与恢复**：经 `--patch` 插件 `dshn-session-bridge.mjs` 读取真实 DSH 会话列表与 surface 历史折叠（损坏文件只读展示、绝不挪动或篡改原文件），支持斜杠命令补全与 `ask_user_question` 原生弹窗。
- **DSH 原生插件管理（含本地导入）**：
  - 侧边栏抽屉与「设置 → 智能体 → 插件管理」双入口，直接对接 DSH `0.2.2-rc.5` 内置的 `pluginManager` 服务。
  - 支持直接离线开关随 DSH 内置的 16 个官方可选插件包，支持输入 npm 包名 / GitHub 仓库或通过 Android 文件选择器导入本地插件包（`.tgz` / `.tar.gz` / `.zip`，自动校验魔数与防路径穿越）安装/卸载插件，自动检测容器内 `pnpm` 并提供一键补装，以及 ACP 非热重载场景下的重启生效引导。
- **Android 14+–17 多分辨率后台虚拟屏**：
  - 通过已授权 Root 或无线 ADB（`shell,v2,raw:`）启动独立 `VirtualDisplayAgent`，直接经 `DisplayManager` 创建受信任虚拟屏并结合 `UiAutomation`、`InputManager` 与 Rust JPEG 实现不打扰主屏的后台操控与实时预览，绕过 OriginOS 等厂商对 `overlay_display_devices` 的限制；支持 5 档持久化分辨率预设（`540×960` / `720×1280` / `900×1600` / `1080×1920` / `1280×720` 横屏）热切换。
- **独立 `:runtime` 进程守护与环境事务**：
  - `specialUse` 前台服务 + 单写文件锁 + `stderr` 环形日志 + 5 秒看门狗 + 有界重启 + 中断轮次 `TOOL_OUTCOME_UNKNOWN` 保护；内置离线环境事务解压、旧版 `.credentials.yaml` 自动迁移、版本快照与格式兼容回滚。
- **双模式隔离终端与安全默认值**：
  - 默认基于 `node-pty`（`DSH_HOME=/root/.dsh-terminal`）提供带快捷键栏的 Ubuntu 容器终端；显式授权 Root 后可切换宿主机 Root 终端。
  - 所有设备读屏、无障碍操作、后台虚拟屏、无线 ADB 与 Root 开关 **默认全关**；高危操作逐次弹窗确认；ADB (`AdbArgvPolicy`) 与 Root (`RootCommandPolicy`) 均执行结构化 `argv` 策略校验，全模式强制禁止访问应用私有 `files/security/` 密钥与审计目录。

详细原理与双机真机实测数据见：
- [Rust 性能核心与后台虚拟屏架构文档](docs/architecture/rust-perf-and-virtual-display.md)
- [11 模块化分层架构说明](docs/architecture/modularization.md)
- [项目总览与演进记录](PROJECT.md)

---

## 构建与测试

环境要求：JDK 21、Android SDK Platform 36（`compileSdk 36`、`minSdk 30`、`arm64-v8a`）；预编译 Rust `.so` 已随仓库提供，如需重编 Rust 核心另需 Rust 工具链与 Android NDK r27c。

```bash
./gradlew testDebugUnitTest :app:assembleStagingRelease
# 开发期跳过 115 MB 离线环境资产打包（APK 约 5 MB）：
./gradlew :app:assembleStagingRelease -PdshnSkipBundledEnv=true
```

许可证、第三方声明与 Ubuntu rootfs GPL 源码书面提供条款见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)。
