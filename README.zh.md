# DSHN — DeepSeek Harness Native (原生安卓客户端)

[English](README.md) | 中文说明

DSHN 是基于 Android 原生 Jetpack Compose (Material 3) 与 [`bxroot`](https://github.com/qiannianhuanxiang/bxroot)（`arm64-v8a`）构建的 [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/dsh) 原生安卓客户端，在 **不修改任何 DSH 源码** 的前提下，通过 ACP 协议、只增式 `--patch` 插件及标准 MCP 服务器，在手机本地提供完整的 Agent 对话、多会话历史恢复、真实 PTY 终端以及默认关闭的可选设备桥接能力。

## 核心架构

- **Rust 原生加速核心（`feat/rust-perf-engine` / `v0.4.0-perf`）**：
  - `libdshn_core.so`（16 KB 页对齐 AArch64 JNI，2.16 MB）：单趟流式 `.tar.gz`/`.tar.zst` 零拷贝解压与硬件 SHA-256、`rustls`+`ring` 原生极速直连（容器冷启期间 ~0.25 s 即可对话）、虚拟屏 RGBA `DirectByteBuffer` → FNV-1a 脏帧跳过 → SIMD JPEG 编码。
  - `libdshn_mcp_relay.so`（393 KB 静态 musl）：取代原先每个会话多起的 Node 24 V8 中继进程，直连宿主机 UDS。
  - 启用 R8 Release 裁剪（DEX `-94.5%`、APK `-60 MB`），在 Android 17（SM8850）上冷启动进入 **~95 ms**、常驻内存下降 **30%**；详见 [Rust 性能核心与后台虚拟屏架构文档](docs/architecture/rust-perf-and-virtual-display.md)。
- **Android 14+–17 后台虚拟屏**：通过已授权 Root 或无线 ADB（`shell,v2,raw:`）启动独立 `VirtualDisplayAgent`，直接经 `DisplayManager` 建立受信任虚拟屏并结合 `UiAutomation`、`InputManager` 与 Rust JPEG 实现不打扰主屏的后台操控与实时预览，绕过 OriginOS 等厂商对 `overlay_display_devices` 的限制。
- **独立 `:runtime` 进程守护 + `specialUse` 前台服务**：容器运行时与主界面隔离；内置单写文件锁、`stderr` 环形日志、5 秒本地看门狗、1 分钟最多 3 次有界重启、流异常降级和中断轮次 `TOOL_OUTCOME_UNKNOWN` 重试提示；集成 `NODE_COMPILE_CACHE` 与 MIT 许可的 DSH Sugar `profile-resolution` 引导加速。
- **零侵入式双向桥接**：
  - `assets/dshn/dshn-bridge.patch.yml` + `dshn-session-bridge.mjs` 通过 `--patch` 挂入 Cordis 上下文，提供只读会话列表 / surface 历史折叠（损坏文件只读展示、绝不移动/篡改）、slash 命令及 `ask_user_question` 交互弹窗。
  - `DeviceBridgeMcpServer` 监听应用私有目录 `files/bridge/device-mcp.sock`，容器内优先由静态 Rust `/opt/dshn/dshn-mcp-relay`（回退 `dshn-mcp-relay.mjs`）通过 ACP `mcpServers` 按需挂载。
- **设备桥接、可选 Root 授权与安全默认值**：
  - 适配最新 `@deepseek-ai/dsh@0.2.2-rc.5`（含 `CONTENT_REFUSED` 识别、旧版 `.credentials.yaml` 自动迁移、自定义端点/搜索提供方、ACP 能力探测、`zstd` 回滚版本检查与事务化容器内升级）。
  - 所有设备读取、屏幕无障碍控制、后台虚拟屏、无线 ADB 与 **宿主机 Root (`su`)** 开关 **默认全关**；Root 授权仅通过 Magisk / KernelSU / SukiSU / APatch 等标准超级用户管理器经用户交互授予，支持会话 TTL 自动过期与一键撤销锁定。
  - 所有高风险操作（点击、滑动、文本输入、全局按键、`device_adb_exec`、`device_root_exec`、`device_virtual_display_*`）默认开启逐次弹窗确认并附带风险警示；ADB (`AdbArgvPolicy`) 与 Root (`RootCommandPolicy`) 均采用结构化 `argv` 策略校验，且 Root 通道在所有模式下强制禁止访问应用自身 `files/security/` 密钥与审计目录，越权一律返回 `[POLICY_BLOCKED]` 并记录脱敏审计日志。
- **双模式隔离终端**：默认使用 DSH 内置 `node-pty` 与 `DSH_HOME=/root/.dsh-terminal` 运行容器 Ubuntu Shell（配齐 `ESC / TAB / CTRL / 方向键` 与实时按键模式）；在用户显式授予 Root 权限后，可切换至带红色警示标记的 Android 宿主机 Root (`su`) 终端。

## 构建与测试

环境要求：JDK 21、Android SDK Platform 36（`compileSdk 36`、`minSdk 30`、`arm64-v8a`）；如需重编 Rust 原生库另需 Rust + Android NDK r27c（预编译 `.so` 已随仓库提供）。

```bash
./gradlew testDebugUnitTest :app:assembleStagingRelease
# 开发期跳过 115 MB 离线环境打包：
./gradlew :app:assembleStagingRelease -PdshnSkipBundledEnv=true
```

许可证、第三方声明与 Ubuntu rootfs GPL 源码书面提供条款见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
