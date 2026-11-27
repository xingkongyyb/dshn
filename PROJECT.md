# DSHN 项目文档

> **DeepSeek Harness Native** —— 在 Android 上以原生 App 运行完整 DeepSeek Harness 智能体。
> 默认免 ROOT（亦支持已 Root 设备的可选标准 `su` 授权）、免 Termux、开箱内置离线运行环境（Ubuntu `24.04.5` + Node `24.22.3` + ACP Slim 裁剪版 DSH `0.2.2-rc.5`）、功能集成零 DSH 源码补丁（仅保留 1 处可自动跳过的 MIT `profile-resolution` 启动热优化）。双层混合架构（Android Bionic + Rust 原生性能核心 / `bxroot` + Ubuntu 24.04 glibc 容器）+ 原生 Compose UI + 标准 ACP 协议。
> 本文档是项目 living document：进度、架构、踩坑、剩余路线图。最后更新 2026-11-27（`feat/rust-perf-engine` / `v0.4.0-perf`：Rust 原生性能核心、R8 裁剪、Android 14+–17 多分辨率后台虚拟屏、DSH 插件管理与本地包导入、交互式上下文与缓存命中率透视、Fast Chat 无损上下文衔接、长流式防截断与 CI 扩展，详见 [docs/architecture/rust-perf-and-virtual-display.md](docs/architecture/rust-perf-and-virtual-display.md)）。

---

## 1. 项目概述与 `perf` 含义

DSHN 让用户在手机上拥有一个完整的 DSH 编码智能体：原生界面对话、流式输出、工具调用、审批、插件管理、上下文与缓存命中率透视，agent 在手机本地的 Ubuntu 容器里执行真实命令。

- **与 DSHA 的区别**：DSHA 是 WebView 包壳（web 崩溃 / UI 不符安卓习惯 / 更新易损坏）；DSHN 全原生 Compose，经标准 ACP 协议与只增式 `--patch` 桥接驱动 DSH，功能集成不改上游源码，内置事务化离线环境。
- **什么是 `perf`（`v0.4.0-perf` / `feat/rust-perf-engine`）**：`perf` 是 **Performance（性能优化版）** 的缩写，并非 Linux `perf` 工具，指面向移动端做的四项系统级性能优化：① 将解压 / 极速直连 / 虚拟屏编码 / MCP 中继 4 个热点下沉到 AArch64 **Rust 原生库**；② 启用 **R8 Release 裁剪**（DEX `-94.5%`、APK `-60 MB`）；③ 冷启动与容器引导加速（主线程去 I/O、`NODE_COMPILE_CACHE`、幂等 MIT `profile-resolution` 加速、40 ms 流式聚合与 O(N) Markdown 渲染，Android 17 冷启动进入 `~95 ms`、内存降 `30%`）；④ 容器冷启期间由 Rust 极速直连提供约 `0.25 s` 秒开对话，并在容器就绪后经 `FastChatHandoff` 无损衔接至 DSH 会话。

**核心设计文档**：`Documents/1/_docs/DSHN原生安卓应用设计_20261109.md`（v2 审计修订版）
**架构专题文档**：[docs/architecture/rust-perf-and-virtual-display.md](docs/architecture/rust-perf-and-virtual-display.md)、[docs/architecture/modularization.md](docs/architecture/modularization.md)
**集成面 spike 证据**：`spike/INTEGRATION-SPIKE-REPORT.md`

---

## 2. 当前进度

### 2.1 已验证里程碑（真机 vivo V2352A / Android 16）

| 里程碑 | 状态 | 验证方式 |
|---|---|---|
| 原生 Compose App 构建/安装/启动 | ✅ | BUILD SUCCESSFUL，真机启动 |
| bxroot 运行时安全 vendoring | ✅ | 官方源 + 双重 SHA256 + AArch64 + 16KB 对齐 |
| 容器栈引导 | ✅ | Ubuntu 24.04 + Node 24.19.0 (`>=24.9.0`) + DSH `0.2.2-rc.5`（含事务化升级器） |
| 容器网络 | ✅ | 修 resolv.conf，fetch 官方 registry 成功 |
| 原生模块 | ✅ | node-pty `PTY_OK` |
| bxroot selftest | ✅ | 9 项全 OK（fail=0） |
| ACP 集成面 spike | ✅ | initialize/session new/list/close/resume/选模型/cancel/MCP-HTTP |
| Kotlin ACP 客户端 | ✅ | 纯 Kotlin，真机连接成功 |
| 原生对话 UI | ✅ | 流式渲染 + 工具卡片 + 审批弹窗 + 取消 |
| **完整对话闭环** | ✅ | UI→ACP→容器→DSH→DeepSeek 模型→流式响应 |

**端到端实证**：
```
你：Say hello in one short sentence
DSH：Hello! It's great to meet you — how can I help today?
```

### 2.2 完成度（对照设计 v2，2026-11-27 更新）

| 设计模块 | 完成度 | 说明 |
|---|---|---|
| §7.1 integration-client（ACP） | 🟢 100% | 纯 Kotlin `AcpClient`：`initialize` / `session/new` / `session/resume` / `session/list` / `session/close` / `set_config_option` / `mcpServers` / `request_permission` / `badLines` 监测；stdout 内联有序投递 + 无界 `Channel` + `LargePayloadXfer` 消除长输出尾块截断 |
| §7.2 session-history | 🟢 100% | 只增式 `--patch` Cordis 插件 `dshn-session-bridge.mjs`（UDS `/run/dshn-bridge/session-bridge.sock`）+ `SurfaceHistoryFold` + `FastChatHandoff` 无损拆包；损坏文件只读标记，不挪不改 |
| §7.3 runtime-supervisor | 🟢 95% | 独立 `:runtime` 进程 `RuntimeService`（AIDL）+ `RuntimeSupervisor`：单写文件锁 + 孤儿 PID 清理 + stderr 环形日志 + 5s 看门狗 + 60s 内最多 3 次有界重启 + `TOOL_OUTCOME_UNKNOWN` + `@file:` 大负载传输 |
| §7.4 env-manager | 🟢 90% | `EnvManager` + Rust `extractor.rs`：APK 内置离线包（Ubuntu `24.04.5` + Node `24.22.3` + ACP Slim DSH `0.2.2-rc.5`）零拷贝 SHA-256 事务解压、`opt/dshn/` 同步、旧版 `.credentials.yaml` 迁移、两阶段 `~/.dsh` 快照与 `session.vN` 回滚兼容校验、容器自检（待补多版本目录切换与协议 `initialize` 升级冒烟） |
| §7.5 device-bridge | 🟢 95% | `DeviceBridgeMcpServer`（UDS `device-mcp.sock` + 静态 Rust `dshn-mcp-relay`）：13 个固定工具默认全关；无障碍读屏/操控 + **Android 14+–17 后台虚拟屏（`DisplayManager` + `UiAutomation` + `InputManager` + Rust SIMD JPEG + 5 档可选分辨率，已绕过 OriginOS `overlay_display_devices` 禁用限制）** + 无线 ADB（NSD/TLS/`tcpip 5555` + `AdbArgvPolicy`）+ 可选标准 `su` Root（`RootCommandPolicy`）+ `ConfirmationBroker` + `AuditLogger`（待补文件交换、通知/分享工具与 SPAKE2+ 配对） |
| §7.6 app-ui | 🟢 100% | 对话优先的原生 Material 3 UI（11 模块）：首启向导（`SetupScreen`）→ 对话主页（`ChatScreen`，左侧抽屉按日期分组会话 + 搜索 + 插件管理入口、O(N) Markdown 渲染、思考/工具卡片、单行模型与思考强度下拉菜单、**交互式上下文与缓存命中率透视弹窗**）→ **插件管理页（`PluginsScreen`，16 内置包开关 / npm / GitHub / SAF 本地 `.tgz`·`.zip` 导入 / 一键装 `pnpm`）**、分组设置页、`DeviceBridgeScreen`（含实时虚拟屏预览与分辨率切换）、`TerminalScreen`、`DeviceLabScreen` |
| §8 威胁模型/bind 集 | 🟢 100% | 仅 bind `/dev` `/proc` `/sys` 与 `0700` `files/bridge:/run/dshn-bridge`（可选显式工作区），禁用备份导出；`files/security/` 出 bind 集且 Root 全模式硬禁；UDS 权限 `0600`；ADB/Root 严苛 `argv` 白名单与逐次确认 |
| §9 保活 | 🟢 90% | `foregroundServiceType="specialUse"` + 轮次内 `PARTIAL_WAKE_LOCK` + 通知权限/电池白名单引导 + 幽灵进程 `device_config` 指引（含 Root 一键修复） |
| §16 开源设施 | 🟢 95% | `LICENSE` (MIT)、`THIRD_PARTY_NOTICES.md`（含 Ubuntu GPL 源码要约、Node `24.22.3` 与非官方商标声明）、`CHANGELOG.md`、中英双语 `README`、GitHub Actions CI（覆盖 JS 语法、Rust 单测、JVM 单测与 R8 `stagingRelease`）、Issue/PR 模板；待正式 Release 签名（P3-6） |

**后台虚拟屏演进说明**：
- 2026-11-13 P2-3 初次 spike 证实 OriginOS 禁用了 `settings put global overlay_display_devices`。
- **2026-11-27 `v0.4.0-perf` 已彻底攻克**：改为通过已授权 Root 或无线 ADB（`shell,v2,raw:`）启动 `app_process` 代理 `VirtualDisplayAgent`，直接调用 `DisplayManager.createVirtualDisplay` + `ImageReader` + `UiAutomation` + `InputManager` + Rust SIMD JPEG，在 Android 16/17 两台 vivo 真机实测均流畅通过（`37 ms` 建屏、`39.9 ms` 截屏+树、`3.7 ms` 脏帧跳过），并支持 5 档分辨率预设热切换。
- 实测内核 `6.1.145-android14-11-maybe-dirty` 在 `bxroot` 内仍不支持 Landlock（`landlock-run` 退 125）：`bash` 执行需使用 `danger-full-access`，`workspace-write` 与 `read-only` 依靠 `fs-sandbox` + 审批拦截。

---

## 3. 架构与组件（`v0.4.0-perf` 双层混合运行时 + 11 模块结构）

DSHN 采用 **双进程隔离**（`:app` UI 进程与 `:runtime` 容器守护进程）和 **三层混合运行时**：
- **Android Bionic 原生层**：Kotlin/Compose UI 与 Rust JNI 核心（`libdshn_core.so`）直接运行在 Android Bionic 上，**零翻译、零开销**。
- **`bxroot` + Ubuntu 24.04 glibc 容器层**：Node 24、DSH `0.2.2-rc.5` 与 `node-pty` 以原生 ARM64 指令运行（**零指令集翻译**），仅文件系统路径映射经 `bxroot` 的 `LD_PRELOAD` 轻量拦截（远轻于 `proot` `ptrace`）。
- **静态 musl 中继层**：`dshn-mcp-relay`（393 KB 静态 `aarch64-unknown-linux-musl`）零 libc 依赖，取代多余的 Node 24 V8 中继进程，直连宿主机 UDS。

```text
┌─────────────────── :app 进程 (Android Bionic · Kotlin/Compose + Rust JNI) ───────────────────┐
│  11 个 Gradle 模块：:app + :core:{data,security,runtime,bridge,ui} + :feature:{5 个页面}       │
│  ├─ ChatScreen（模型选择 / 上下文与缓存命中率透视弹窗 / 流式渲染 / 工具与提问卡片）             │
│  ├─ PluginsScreen（抽屉 + 设置双入口：DSH pluginManager 开关/装卸/一键补装 pnpm）              │
│  ├─ DeviceBridgeScreen + VirtualDisplayController（Root / 无线 ADB 后台虚拟屏与实时预览）      │
│  ├─ DshnNativeCore ──JNI──▶ libdshn_core.so（零拷贝解压 / 0.25s 极速直连 / SIMD JPEG）         │
│  └─ DeviceBridgeMcpServer（UDS: files/bridge/device-mcp.sock, 0600）                          │
└───────────────────────────────┬────────────────────────────────┬──────────────────────────────┘
                                │ AIDL + Unix Socket             │ su / ADB shell,v2,raw
                                ▼                                ▼
┌──────────── :runtime 进程 (bxroot + Ubuntu 24.04 glibc) ────────────┐ ┌── app_process ──┐
│  RuntimeService (specialUse 前台服务) + RuntimeSupervisor            │ │ VirtualDisplay  │
│  ├─ dsh --profile acp --patch /opt/dshn/dshn-bridge.patch.yml       │ │ DisplayManager  │
│  ├─ dshn-session-bridge.mjs (会话 / context.inspect / plugins.* /   │ │ ImageReader     │
│  │    commands / ask_user_question)                                 │ │ UiAutomation    │
│  └─ /opt/dshn/dshn-mcp-relay (393 KB 静态 Rust musl，直连宿主机 UDS) │ │ InputManager    │
└─────────────────────────────────────────────────────────────────────┘ └─────────────────┘
```

### 核心模块与源码清单

| 模块 / 路径 | 职责 |
|---|---|
| `app/` | 应用壳、`DshnApplication`、`MainActivity`、`AppContainer`（显式构造注入）、`DshnNavHost`（类型安全路由）与 `assets/dshn/`（只增式 `--patch` 脚本） |
| `rust/dshn-core` & `rust/dshn-mcp-relay` | 16 KB 页对齐 AArch64 Rust 原生库：`libdshn_core.so`（解压 / 极速直连 / 虚拟屏 JPEG 编码）与 `libdshn_mcp_relay.so`（静态 musl MCP 中继） |
| `core/runtime/` | `AcpClient`、`SurfaceHistoryFold`、`ContainerLauncher`、`EnvManager`（离线环境解压 / 快照 / 凭据迁移 / `profile-resolution` 加速）、`SessionBridgeClient`、`ContextAndPluginModels`、`RuntimeService` / `RuntimeSupervisor` / `RuntimeController` / `RuntimeSessionCoordinator`、`TerminalPtySession`、`HostRootShellSession` |
| `core/bridge/` | `DeviceBridgeMcpServer`（UDS MCP 服务）、`DshnAccessibilityService`、`WirelessAdbClient`、`RootAuthManager`、`ConfirmationBroker`、`VirtualDisplayAgent.java`、`VirtualDisplayController` |
| `core/security/` & `core/data/` & `core/ui/` | Keystore AES-GCM 加密、`AuditLogger`、`AdbArgvPolicy`、`RootCommandPolicy`、`SettingsStore`、Material 3 主题与 `tr()` 双语通用组件 |
| `feature/chat/` | `ChatScreen`、`ChatComposer`、`ModelPickerMenu`、`ContextInspectorMenu`（上下文占用 / 缓存命中率 / System Prompt / Tools / Skills 弹窗）、`SessionDrawer` |
| `feature/settings/` | `SettingsHomeScreen`、`PluginsScreen`（DSH 插件管理）、模型与 API Key、权限与工作区、后台保活、环境与快照、日志诊断、关于 |
| `feature/bridge/` & `feature/terminal/` & `feature/setup/` | 设备桥与后台虚拟屏预览（`DeviceBridgeScreen` / `DeviceLabScreen`）、双模式终端（`TerminalScreen`）、首启设置向导（`SetupScreen`） |

### vendored 与内置运行时

- `app/src/main/jniLibs/arm64-v8a/`：`bxroot` v0.2.5 运行时（见 `vendor/bxroot/PROVENANCE.md`）+ 预编译 `libdshn_core.so` + `libdshn_mcp_relay.so`
- `app/src/main/assets/env/`：APK 内置离线 Ubuntu 24.04.5 + Node.js 24.22.3 + `@deepseek-ai/dsh@0.2.2-rc.5`（首启由 Rust `extractor.rs` 事务化校验解压至 `/data/data/dev.dshn.app/files/linux/ubuntu`，无需电脑手动 `adb push`）

---

## 4. 快速开始

### 4.1 环境要求

- JDK 21、Android SDK（compileSdk 36 / build-tools 36.0.0）、Gradle 8.14.3
- 真机 arm64-v8a，Android 11+（minSdk 30），首验机 vivo V2352A / Android 16
- 中文 Windows 用户名需注意 §5 的构建坑

### 4.2 构建

```bash
cd dshn
# 中文路径环境必须设 ASCII tmpdir（见 §5.1）
export GRADLE_OPTS="-Dfile.encoding=UTF-8"
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 4.3 安装与容器准备

```bash
adb install -r app/build/outputs/apk/stagingRelease/app-stagingRelease.apk
```

APK 已内置离线 Ubuntu `24.04.5` + Node.js `24.22.3` + `@deepseek-ai/dsh@0.2.2-rc.5`（ACP Slim，见 `vendor/env/PROVENANCE.md`），首次打开 App 后在向导中一键通过 Rust `extractor.rs` 事务化校验并离线解压就绪，**不再需要电脑 `adb push` rootfs**。若开发期加了 `-PdshnSkipBundledEnv=true`，仍可按设置页「手动准备步骤（ADB）」推送。

### 4.4 凭据配置

密钥写入容器 `files/linux/ubuntu/root/.dsh/api_key`（纯文本 key，600 权限）。ContainerLauncher 启动时读取注入 `DEEPSEEK_API_KEY` 环境变量。**密钥不入 git、不硬编码。**

---

## 5. 关键技术决策与踩坑记录

> 本节是项目 institutional knowledge，避免重复踩坑。

### 5.1 中文 Windows 用户名构建坑（构建兼容说明）

| 坑 | 根因 | 解法 |
|---|---|---|
| AGP 拒绝非 ASCII 工程路径 | Windows 中文用户目录 | `android.overridePathCheck=true` |
| Kotlin 编译 `.alive` 文件 AccessDenied | JVM NIO 只能写工程目录，Temp 被系统策略拦 | `kotlin.compiler.execution.strategy=in-process` + 命令行 `GRADLE_OPTS` 指定 ASCII `tmpdir` |
| `gradle.properties` 中文路径乱码 | Gradle 按 Latin-1 读 properties，UTF-8 非 ASCII 路径易乱码 | 若本地需要固定 tmpdir，通过环境变量/命令行传 `-Djava.io.tmpdir=...`（或 Unicode `\u` 转义），仓库内 `gradle.properties` 不硬编码本机用户绝对路径 |
| Git Bash 转义 adb/icacls 路径 | MSYS 把 `/data/...`、`/grant` 转成 Windows 路径 | 命令前 `export MSYS_NO_PATHCONV=1` |

### 5.2 bxroot selftest HAS-FAILURE（已根治，commit 6c0e171）

- 失败项 `[4] rootfs`：bxroot init 自检的 stat 钩子对 `/data/user/0/…` 形式报 ENOENT，对 `/data/data/…` 规范形式正常。
- 解法：ContainerLauncher rootfs 用 `/data/data` 规范路径。selftest 9 项全 OK。

### 5.3 容器网络 DNS（已修）

- Ubuntu rootfs 的 `/etc/resolv.conf` 是悬挂符号链接（→ systemd-resolved，容器内未运行）→ DNS EAI_AGAIN。
- 解法：rm 符号链接，写真实 `nameserver 223.5.5.5 / 8.8.8.8`。

### 5.4 模型凭据解析（已修，commit 04aa2cc）

- DSH deepseek provider 经 `apiKeyEnv=DEEPSEEK_API_KEY` 解析；`refs:` yaml 格式未被采用。
- 解法：ContainerLauncher 从容器 `root/.dsh/api_key` 读取，注入 `DEEPSEEK_API_KEY` 环境变量（DSH 错误信息明确支持 launching environment 方式）。

### 5.5 npm 原生模块

- npm allow-scripts 默认拦安装脚本；需 `--allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs` 放行，node-pty 才能 load。

### 5.6 集成面决策（第 0 号决策）

- **选 ACP**，拒 SDK。SDK 无取消/无会话列表/无按会话选模型/审批空壳（审计 B1）；ACP 实测全部具备。
- ACP `session/cancel` 是**通知**（非请求，发请求会 -32601）。
- 本 build ACP `promptCapabilities.image/audio=false`（多模态关闭）→ 需图片输入时评估 API Peer fallback。

### 5.7 真机调试技巧

- 这台 vivo 的 logcat 对 App 日志不可靠 → 用「结果写文件 + run-as cat」或截图读 UI 状态。
- `adb shell input text` 空格用 `%s`，不支持中文。
- 安装需点 vivo 未知来源确认（checkbox + 继续安装）。

---

## 6. 安全模型

- **运行时审查**：bxroot / Ubuntu `24.04.5` / Node `24.22.3` / DSH `0.2.2-rc.5` 均来自官方源并做双重 SHA-256 校验（`vendor/bxroot/PROVENANCE.md`、`vendor/env/PROVENANCE.md`）。
- **凭据隔离**：App 侧 API Key 密文（`secrets.enc`）、ADB 密钥（`adb_identity.enc`）、设置与审计日志（`audit.jsonl`）存放于容器 bind 集之外的 `files/security/`（AndroidKeyStore AES-256-GCM 加密，Root 全模式硬禁访问），源码零硬编码、不入 git；容器内 `root/.dsh/api_key`（`0600`）仅用于运行期注入 `DEEPSEEK_API_KEY`。
- **最小 bind 集**：容器仅 bind `/dev` `/proc` `/sys` 与 `0700` `files/bridge:/run/dshn-bridge`（可选显式单个工作区），**不 bind `/sdcard`**。
- **bxroot 非沙箱**：bxroot 是用户态兼容运行时，不是安全沙箱（上游明示）。agent 与 App 同 UID，威胁模型见设计 §8。
- **已落地设备桥防护**：私有目录 `0600` UDS 传输（`device-mcp.sock` + 静态 Rust `dshn-mcp-relay`，替代 `127.0.0.1` TCP）、默认全关开关、高危操作 `ConfirmationBroker` 逐次确认、ADB (`AdbArgvPolicy`) 与 Root (`RootCommandPolicy`) 严格结构化 `argv` 白名单。

---

## 7. 剩余工作路线图

### P2 — 设备桥 + 会话历史 + 监督加固

| 任务 | 内容 | 验收标准 |
|---|---|---|
| **P2-1 设备桥 MCP server** | App 侧 MCP server（UDS 传输 + 容器内 stdio relay），经 ACP `mcpServers` 挂载 | DSH 能调用设备工具；UDS 文件权限=ACL |
| **P2-2 无障碍能力** | 读屏结构化输出、点按、输入、滑动、截屏 | agent 能读屏并点按；Android 13+ 受限设置引导 |
| **P2-3 虚拟屏** | ADB `overlay_display_devices` + 定向输入（先 spike） | spike 有结论（通过或降级无障碍全屏） |
| **P2-4 ADB 通道** | 无线配对 + argv 白名单（非字符串匹配） | 白名单外命令 `[POLICY_BLOCKED]` |
| **P2-5 会话历史** | session-query MCP 导出历史，surface 投影渲染 | 历史会话可开可续；坏文件只读标记不挪 |
| **P2-6 监督加固** | 看门狗 + 有界重启 + 单写者锁 + 独立 :runtime 进程 | 崩溃自动重启不丢已提交会话；无双写 |
| **P2-7 保活** | 前台服务 specialUse + 幽灵进程引导 + 电池白名单 | 息屏长任务存活；划掉 App 验证 |
| **P2-8 逐次审批** | 高危设备操作默认逐次确认 + 通知/弹窗 | 点按/输入/ADB 默认征询 |

### P3 — 环境事务 + 开源发布

| 任务 | 内容 | 验收标准 |
|---|---|---|
| **P3-1 环境事务** | 整体发行版本（rootfs+Node+DSH+profile lockfile）+ 状态快照 + 回滚格式检查 | 更新中断/关机→启动回滚；快照恢复 |
| **P3-2 设置页** | 模型/API key/权限档位/运行时/更新；Keystore 加密 | key 经 Keystore；权限档位生效 |
| **P3-3 会话列表页** | 多会话管理、切换、新建 | 列表/切换/新建可用 |
| **P3-4 终端页** | PTY 终端（扩展键行 + 简易兜底） | vim 可用 |
| **P3-5 CI** | GitHub Actions：JVM 测试 + arm64 ACP 一致性 + APK 构建 | PR 触发绿 |
| **P3-6 签名发布** | keystore 托管 + GitHub Releases（仅此一渠道） | tagged release |
| **P3-7 双语文档** | README zh/en + issue/PR 模板 + 诊断包 | 开源就绪 |
| **P3-8 许可证合规** | Ubuntu GPL 组件源码要约 + DeepSeek 商标确认 | 合规 |

### 7.2 剩余待办与真机验收项（2026-11-27）

| 项 | 状态 | 说明 |
|---|---|---|
| **v0.4.0-perf 新功能真机验收** | ⏳ 待装机验收 | 最新 `PluginsScreen`（含 SAF 本地插件导入）、`ContextInspectorMenu` 弹窗、5 档虚拟屏分辨率切换、Fast Chat → ACP 上下文衔接与长流式防截断已过全部 JVM/Rust 单测与 R8 打包，待装机实测 |
| **设备桥剩余分组（v0.5）** | ⏳ 待实现 | 补齐原设计的文件交换（`Download/DSHN`）、系统交互（通知/震动/分享/打开链接）、按文字点按与 6 位无线 ADB 配对码（SPAKE2+） |
| **升级与数据迁移增强（v0.5）** | ⏳ 待实现 | UI 级 `~/.dsh` 导出/导入备份（防卸载丢数据）、升级支持指定 DSH 版本 + ACP `initialize` 协议冒烟、自定义端点手动拉取 `/models`、DSH 语言同步与 Material You 动态取色 |
| **P3-6 正式签名与发布** | ⏳ 待配置 | release keystore 托管与 GitHub Releases tagged release 流水线；CI 补充 arm64 真 DSH 协议一致性测试与 Compose 截图回归 |
| **双机真机基准回归** | ✅ 已实测通过 | `vivo V2352A`（Android 16）与 `vivo V2618A`（Android 17）已完成 `v0.4.0-perf` 冷启动（`~95 ms`）、内存（`88.7 MB` PSS）、8/8 Agent 工具、静态 Rust MCP 与后台虚拟屏（`37 ms` 建屏）实机验证 |

---

## 8. 已知问题与风险

| 项 | 状态 | 处置 |
|---|---|---|
| ACP 多模态（image/audio）关闭 | 已知 | DSH `0.2.2-rc.5` ACP profile 默认关闭；后续按需补充 `ChatComposer` 选图与 API Peer fallback |
| ACP Slim 裁剪范围 | 已知（刻意取舍） | 内置离线包裁剪了 196 个桌面/Web/语音/Office 包（含 `libreoffice-kit-wasm`、`sherpa-onnx`）以节省 `79.5 MB` 体积；如需完整 npm 包可在环境页重装或按需安装插件 |
| `profile-resolution` 启动优化 | 已知（可控） | `EnvManager` 对 `@deepseek-ai/dsh-app-boot` 做 1 处幂等 MIT 启动热优化，上游变更时自动跳过，不阻塞升级 |
| 协议 pre-stable 漂移 | 风险 | 锁 DSH 目标版本 `0.2.2-rc.5` + 升级前自动快照与自检；后续补充 CI 真 DSH 一致性门禁 |
| bxroot 同 UID 无沙箱 | 固有约束 | 与 Android 同 UID 且部分内核无 Landlock，已在设置页与文档明示，凭据/审计全出 bind 集 |

---

## 9. 提交历史

```
04aa2cc feat: 模型凭据 env 注入 — 完整对话闭环真机跑通
386ce3e feat: P1 ACP 客户端 + 原生对话 UI（真机已连接）
93ed013 docs: spike 报告更新 selftest 已根治
6c0e171 fix: bxroot selftest PASS — rootfs 用 /data/data 规范路径
cf52d20 test: P0-T4 ACP 集成面 spike 证据
ba1e833 feat: P0-T3 容器引导跑通完整 DSH 栈
0c424cb feat: DSHN P0 骨架 + vendored bxroot v0.2.5
```

---

## 10. 参考

- 设计文档：`Documents/1/_docs/DSHN原生安卓应用设计_20261109.md`
- P0 实施计划：`Documents/1/_docs/DSHN-P0实施计划_20261112.md`
- AVF 部署方案（备选路线）：`Documents/1/_docs/DSH移动端AVF部署方案_20261108.md`
- 集成面 spike：`spike/INTEGRATION-SPIKE-REPORT.md`
- bxroot 来源审查：`vendor/bxroot/PROVENANCE.md`
- 上游 DSH：https://github.com/deepseek-ai/deepseek-harness
- bxroot：https://github.com/qiannianhuanxiang/bxroot
- ACP 协议：https://agentclientprotocol.com