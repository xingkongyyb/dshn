# DSHN 项目文档

> **DeepSeek Harness Native** —— 在 Android 上以原生 App 运行完整 DeepSeek Harness 智能体。
> 默认免 ROOT（亦支持已 Root 设备的可选标准 `su` 授权）、免 Termux、零 DSH 补丁。bxroot 用户态容器 + 原生 Compose UI + 标准 ACP 协议。
> 本文档是项目 living document：进度、架构、踩坑、剩余路线图。最后更新 2026-11-27（`feat/rust-perf-engine` / `v0.4.0-perf`：Rust 原生性能核心 + R8 裁剪 + Android 14+–17 后台虚拟屏，详见 [docs/architecture/rust-perf-and-virtual-display.md](docs/architecture/rust-perf-and-virtual-display.md)）。

---

## 1. 项目概述

DSHN 让用户在手机上拥有一个完整的 DSH 编码智能体：原生界面对话、流式输出、工具调用、审批，agent 在手機本地的 Ubuntu 容器里执行真实命令。

**与 DSHA 的区别**：DSHA 是 WebView 包壳（web 崩溃/UI 不符安卓习惯/更新易损坏）；DSHN 全原生 Compose，经标准 ACP 协议驱动 DSH，零补丁，事务化环境。

**核心设计文档**：`Documents/1/_docs/DSHN原生安卓应用设计_20261109.md`（v2 审计修订版）
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

### 2.2 完成度（对照设计 v2，2026-11-13 更新）

| 设计模块 | 完成度 | 说明 |
|---|---|---|
| §7.1 integration-client（ACP） | 🟢 100% | 纯 Kotlin `AcpClient`：`initialize` / `session/new` / `session/resume` / `session/list` / `session/close` / `set_config_option` / `mcpServers` / `request_permission` / `badLines` 流异常监测（JVM 单测通过） |
| §7.2 session-history | 🟢 95% | 只增式 `--patch` Cordis 插件 `dshn-session-bridge.mjs`（UDS `/run/dshn-bridge/session-bridge.sock`）+ `SurfaceHistoryFold`；损坏文件只读标记，不挪不改 |
| §7.3 runtime-supervisor | 🟢 95% | 独立 `:runtime` 进程 `RuntimeService`（AIDL）+ `RuntimeSupervisor`：单写文件锁 + 孤儿 PID 清理 + stderr 环形日志 + 5s 看门狗 + 60s 内最多 3 次有界重启 + `TOOL_OUTCOME_UNKNOWN` + `@file:` 大负载传输 |
| §7.4 env-manager | 🟢 95% | `EnvManager`：fsync 原子部署 `opt/dshn/`、`env-manifest.json`、两阶段 `~/.dsh` 快照与 `session.vN` 回滚兼容校验、容器自检（JVM 单测通过） |
| §7.5 device-bridge | 🟢 95% | `DeviceBridgeMcpServer`（UDS `device-mcp.sock` + stdio relay）：默认全关；`DshnAccessibilityService` 读屏/点击/滑动/输入/截图；P2-3 真机 spike 确认 OriginOS 禁用了 `overlay_display_devices`，降级全屏；`WirelessAdbClient` + `AdbArgvPolicy` + `ConfirmationBroker` + `AuditLogger` |
| §7.6 app-ui | 🟢 100% | v0.3.0 重做为对话优先的原生 Material 3 UI：首启设置向导（`SetupScreen`）→ 对话主页（`ChatScreen`，左侧抽屉按日期分组的会话列表 + 搜索、Markdown 渲染、思考/工具卡片、模型与思考强度底部弹层、上下文用量）→ 分组设置页（模型与 API Key / 执行权限与工作目录 / 后台运行 / 运行环境与快照 / 日志诊断 / 关于）、`DeviceBridgeScreen`（设备桥与 Root）、`TerminalScreen`、`DeviceLabScreen`（调试台）；全部文案中英双语（`tr()`），浅色/深色/跟随系统，自带矢量自适应图标 |
| §8 威胁模型/bind 集 | 🟢 100% | 仅 bind `/dev` `/proc` `/sys` 与 `0700` `files/bridge:/run/dshn-bridge`（可选显式工作区），禁用备份导出；UDS 权限 `0600`；ADB 严苛 `argv` 白名单与逐次确认 |
| §9 保活 | 🟢 90% | `foregroundServiceType="specialUse"` + 轮次内 `PARTIAL_WAKE_LOCK` + 通知权限/电池白名单引导 + 幽灵进程 `device_config` 指引 |
| §16 开源设施 | 🟢 95% | `LICENSE` (MIT)、`THIRD_PARTY_NOTICES.md`（含 Ubuntu GPL 源码要约）、`CHANGELOG.md`、中英双语 `README`、GitHub Actions CI、Issue/PR 模板；待发布签名（P3-6） |

**2026-11-13 P2-3 真机虚拟屏 Spike 结论（vivo V2352A / Android 16）**：
- `adb shell "settings put global overlay_display_devices 720x1280/240"` 退出码 0，但 `settings get` 仍为 `null` 且 `dumpsys display` `OverlayDisplayAdapter` `mOverlays: size=0`；`cmd display` 无创建虚拟屏命令。因此默认降级为无障碍全屏交互，保留 `--display`/`display_id` 兼容参数。
- 实测内核 `6.1.145-android14-11-maybe-dirty` 在 `bxroot` 内不支持 Landlock（`landlock-run` 退 125）：`bash` 执行需使用 `danger-full-access`，`workspace-write` 与 `read-only` 依靠 `fs-sandbox` + 审批拦截。

---

## 3. 架构与组件

```
┌──────────── App 进程（Kotlin/Compose）────────────┐
│ MainActivity → ChatScreen（Compose）              │
│ ChatViewModel（StateFlow，事件溯源渲染）           │
│ AcpClient（纯 Kotlin ACP JSON-RPC over stdio）     │
│ AcpSessionManager（进程生命周期 + stderr 排空）    │
│ ContainerLauncher（bxroot 启动封装）              │
└──────────────────────────────────────────────────┘
        stdio ↕ ACP JSON-RPC
   bxroot 容器（Ubuntu 24.04 + Node 24 + DSH 0.2.2-rc.1）
        └ dsh --profile acp
```

### 源码清单（`app/src/main/java/dev/dshn/app/` 及 `assets/dshn/`）

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 入口：首启向导闸门、路由回退栈（`AppRoute` + 动画切换 + 系统返回键）、带操作按钮的提示条、状态栏明暗适配与全局权限/确认弹窗托管 |
| `acp/AcpClient.kt` | ACP JSON-RPC 2.0 客户端（含 `mcpServers`、`resume`、动态 `configOptions`、流损坏监测） |
| `acp/SurfaceHistoryFold.kt` | 把 `sessionQuery.readSurface()` 事件折叠为 UI 历史卡片并识别 `TOOL_OUTCOME_UNKNOWN` |
| `runtime/ContainerLauncher.kt` | bxroot 启动封装：最小 bind 集（含 `0700` `bridge:/run/dshn-bridge`）、`--patch` 支持 |
| `runtime/EnvManager.kt` | `env-manifest.json`、原子部署 `assets/dshn/`、DNS 修复、`~/.dsh` 事务快照与回滚兼容检查、容器自检 |
| `runtime/SessionBridgeClient.kt` | UDS 客户端：会话列表、surface 历史、slash 命令、交互式 `ask_user_question` 订阅 |
| `runtime/RuntimeSupervisor.kt` / `RuntimeService.kt` / `RuntimeController.kt` | 独立 `:runtime` 进程 AIDL 守护、`specialUse` 前台服务、单写锁、5s 看门狗、有界重启、Turn WakeLock、`@file:` 大负载传递 |
| `runtime/TerminalPtySession.kt` | 基于容器内 `node-pty` 与独立 `DSH_HOME=/root/.dsh-terminal` 的终端控制器 |
| `bridge/DeviceBridgeMcpServer.kt` / `DshnAccessibilityService.kt` / `WirelessAdbClient.kt` / `RootAuthManager.kt` / `ConfirmationBroker.kt` | 设备桥 MCP 服务（UDS，11 个固定工具含 `device_root_status` / `device_root_exec`）、无障碍读屏/截图/手势、NSD + Plain-TCP/TLS ADB 客户端、标准 `su` Root 授权与 TTL 管理、高危操作逐次确认弹窗 |
| `runtime/HostRootShellSession.kt` | 基于已授权 `su` 的宿主机 Root 交互终端会话控制器 |
| `security/KeystoreCrypto.kt` / `AuditLogger.kt` / `AdbArgvPolicy.kt` / `RootCommandPolicy.kt` | Keystore AES-GCM 加密、受限 `audit.jsonl` 审计、严苛 ADB / Root 结构化 `argv` 策略（硬隔离 `files/security/`） |
| `data/SettingsStore.kt` | 加密凭据与权限/设备桥（默认全关）/浅色默认与深浅外观设置持久化 |
| `ui/DshnTheme.kt`, `UiComponents.kt`, `MarkdownText.kt`, `SystemHelpers.kt`, `MainViewModel.kt`, `SetupScreen.kt`, `ChatScreen.kt`, `SettingsScreen.kt`, `DeviceBridgeScreen.kt`, `TerminalScreen.kt`, `DeviceLabScreen.kt` | 主题与 `tr()` 双语、品牌标识/设置行/横幅等通用组件、轻量 Markdown 渲染、统一状态与导航管理、首启向导与各业务界面 |
| `assets/dshn/{dshn-bridge.patch.yml,dshn-session-bridge.mjs,dshn-mcp-relay.mjs,dshn-pty-runner.mjs}` | 只增式 Cordis `--patch` 插件、MCP UDS 转发器与 `node-pty` 启动器 |

### vendored 运行时

- `app/src/main/jniLibs/arm64-v8a/`：bxroot v0.2.5 八件套（见 `vendor/bxroot/PROVENANCE.md`）
- 容器 rootfs：`/data/data/dev.dshn.app/files/linux/ubuntu`（Ubuntu 24.04，首启需推送，见 §4）

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
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 推送 Ubuntu rootfs（官方 cloud-images arm64，SHA256 校验后）
adb push ubuntu-24.04-server-cloudimg-arm64-root.tar /data/local/tmp/
adb shell run-as dev.dshn.app mkdir -p files/linux/ubuntu
adb shell run-as dev.dshn.app tar -xf /data/local/tmp/...tar -C files/linux/ubuntu
# Node 24 解到 files/linux/ubuntu/opt/node24（symlink）
# DSH：容器内 npm install -g --allow-scripts=... @deepseek-ai/dsh@0.2.2-rc.1
```

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

- **运行时审查**：bxroot/Ubuntu/Node 均官方源 + SHA256 双重校验 + 包体审查（`vendor/bxroot/PROVENANCE.md`）。
- **凭据隔离**：API key 仅存手机容器（600 权限），运行时读取注入，源码无硬编码、不入 git。
- **最小 bind 集**：容器仅 bind `/dev` `/proc` `/sys`，**不 bind /sdcard**（凭据区/文件授权不被绕过）。
- **bxroot 非沙箱**：bxroot 是用户态兼容运行时，不是安全沙箱（上游明示）。agent 与 App 同 UID，威胁模型见设计 §8——设备桥密钥须出 bind 集 + Keystore 加密（P2 落实）。
- **待落实**：设备桥 UDS 传输（防 localhost TCP 抢占）、逐次高危操作确认、ADB argv 白名单。

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

### 7.2 剩余待办与真机验收项

| 项 | 状态 | 说明 |
|---|---|---|
| **P3-6 签名发布** | ⏳ 待配置 | release keystore 托管与 GitHub Releases 渠道待后续发布时配置 |
| **真机端到端回归（vivo V2352A）** | ✅ 已实测通过 | `app-debug.apk`（`2026-11-14`）真机通过：`:runtime` + `session-bridge.sock`（`HEALTHY`）、冷启历史恢复、实时 ACP 流式对话、会话列表、`node-pty` `/bin/bash -il` 终端、设备桥试验台（`tcpip 5555` + RSA 授权弹窗与 `[POLICY_BLOCKED]` + `audit.jsonl`）、浅色默认 UI 与全局深浅一键切换；注：无障碍受限设置本轮跳过 |

---

## 8. 已知问题与风险

| 项 | 状态 | 处置 |
|---|---|---|
| ACP 多模态（image/audio）关闭 | 已知 | 需图片输入时评估 API Peer fallback |
| 会话不持久化到 UI | 已解决（P2-5） | `session-bridge.sock` + `SurfaceHistoryFold` 持久化与恢复历史轮次 |
| 环境更新非事务 | 已解决（P3-1） | `EnvManager` 清单校验、自检与快照创建/回滚已接入 |
| 无保活 | 已解决（P2-7） | 独立 `:runtime` + `RuntimeService` 前台服务 + 有界自动重启 |
| 设备桥未做 | 已解决（P2-1~P2-8） | `device-mcp.sock` 默认关闭、逐次确认与 ADB argv 白名单已接入 |
| 协议 pre-stable 漂移 | 风险 | 锁 DSH 版本区间 + 升级冒烟（已兼容 `0.2.2-rc.1` `formatStatus: "current"`） |
| bxroot 上游修复跟踪 | 风险 | vendored 锁 v0.2.5 + SHA256 |

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