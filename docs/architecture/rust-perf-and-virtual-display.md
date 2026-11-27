# DSHN Rust 原生性能核心与 Android 14+ 后台虚拟屏架构文档

> 适用分支：`feat/rust-perf-engine`（版本号 `0.4.0-perf`，`versionCode = 6`）
> 实机验证平台：`vivo V2352A`（iQOO Z9 Turbo，Android 16 / API 36）与 `vivo V2618A`（SM8850，Android 17 / API 37）
> 核心原则：**保留 `bxroot` 用户态容器、保留原有 `main` / `aistudio-refactor` 分支、不修改上游 DSH 源码文件**

---

## 1. 背景、目标与 `perf` 含义

> **`perf` 是什么？**
> 分支 `feat/rust-perf-engine` 与版本 `0.4.0-perf` 中的 **`perf`** 是 **Performance（性能优化版）** 的简称（并非 Linux `perf` 采样工具）。它代表在不放弃完整 DSH/Linux 能力的前提下，把移动端最吃 CPU/内存/启动时间的环节全部重构提速。

在 `v0.3.2` 及模块化重构（`aistudio-refactor`）基础上，实机性能剖析暴露了 5 处关键瓶颈：

1. **包体与冷启动偏重**：Debug 构建未启用 R8 裁剪（APK `179.99 MB`，13 个 DEX 共 `54.71 MB`），且 `AppContainer` 在主线程执行文件与 Socket 初始化，冷启动耗时约 `471 ms`–`522 ms`。
2. **解压与文件 I/O 开销高**：离线环境（Ubuntu + Node 24 + DSH）解压走 JVM 流与反射调用 `android.system.Os`，首次释放慢且内存抖动明显。
3. **容器启动期间无法对话**：冷启需等待 `bxroot` → Node 24 → DSH ACP 初始化（数秒），期间用户只能干等。
4. **MCP 桥接额外起第二个 Node 24 进程**：旧版每个 ACP 会话都会在容器内再拉起一个完整 V8 进程跑 `dshn-mcp-relay.mjs`，浪费内存与启动时间。
5. **OriginOS 等厂商系统禁用 `overlay_display_devices`**：传统命令行虚拟屏方案失效，Agent 操控手机时会打断用户主屏。

`feat/rust-perf-engine` 围绕上述瓶颈引入了 **Rust 原生核心（JNI + 静态 musl）**、**R8 Release 裁剪**、**冷启动与 DSH 引导加速**、**Android 14+–17 后台虚拟屏**，并在不改上游源码的前提下经 `dshn-session-bridge.mjs` 扩展了 **DSH 原生插件管理** 与 **上下文 / 缓存命中率实时透视弹窗**。

---

## 2. 总体架构（Bionic 原生 + Ubuntu glibc 容器 + 静态 musl）

| 运行层 | 运行环境 / C 库 | 执行特点与开销说明 |
|---|---|---|
| **`:app` 主进程与 `libdshn_core.so`** | Android 原生 **Bionic** | 纯原生 `arm64-v8a`，**零翻译、零额外开销** |
| **`:runtime` 容器内的 Node 24 + DSH** | `bxroot` + Ubuntu 24.04 **glibc** | CPU 指令全原生 ARM64（**零架构翻译**）；仅文件/路径访问经过 `bxroot` 的 `LD_PRELOAD` 用户态轻度拦截（远轻于 `proot` 的 `ptrace` 拦截） |
| **`/opt/dshn/dshn-mcp-relay`** | 静态链接 **musl**（零 libc 依赖） | 393 KB 静态二进制，直连宿主机 UDS，消除每个会话多余的 Node V8 进程 |

```text
┌──────────────────────── :app 进程 (Android Bionic · Kotlin/Compose) ─────────┐
│  MainActivity / ChatScreen (上下文透视弹窗) / PluginsScreen / DeviceBridge    │
│  ├─ RuntimeSessionCoordinator (40ms 流式聚合 + 0.25s Rust 极速直连回退)       │
│  ├─ DshnNativeCore ──JNI──▶ libdshn_core.so (Rust, 16KB 对齐, 2.16 MB)        │
│  │    ├─ extractor.rs  : 零拷贝 APK 切片流式解压 (.tar.gz / .tar.zst) + SHA256│
│  │    ├─ fast_chat.rs  : 原生 rustls/ring TLS + 零拷贝 SSE 极速直连           │
│  │    └─ frame.rs      : RGBA DirectByteBuffer → FNV-1a 脏帧跳过 → SIMD JPEG  │
│  ├─ DeviceBridgeMcpServer (宿主机 UDS: files/bridge/device-mcp.sock, 0600)    │
│  └─ VirtualDisplayController (Root su 或 Wireless ADB shell,v2,raw 双向管道)  │
└───────────────────────────┬──────────────────────────┬───────────────────────┘
                            │ AIDL + UDS               │ su / ADB shell,v2,raw
                            ▼                          ▼
┌────────── :runtime 进程 (bxroot + Ubuntu glibc) ────────┐  ┌─ app_process (uid 0/2000) ─┐
│  libbxroot.so + Ubuntu 24.04 + Node 24 + DSH 0.2.2-rc.5│  │ VirtualDisplayAgent.java   │
│  ├─ NODE_COMPILE_CACHE=/root/.cache/dshn-v8 (带自愈)    │  │ ├─ DisplayManager 独立虚拟屏 │
│  ├─ DSH Sugar profile-resolution 幂等加速补丁           │  │ ├─ ImageReader RGBA Surface│
│  ├─ dshn-session-bridge.mjs (--patch 桥接：历史/提问/   │  │ ├─ 加载 libdshn_core.so    │
│  │    context.inspect 上下文与缓存命中率 / plugins.*)   │  │ ├─ UiAutomation 界面树      │
│  └─ /opt/dshn/dshn-mcp-relay (393KB 静态 Rust musl)     │  │ └─ InputManager 直接触控   │
│       └─ 直连 DSHN_DEVICE_MCP_HOST_SOCK ──▶ 宿主机 UDS  │  └────────────────────────────┘
└─────────────────────────────────────────────────────────┘
```

---

## 3. Rust 原生引擎（`rust/`）

Cargo 工作区位于 [rust/Cargo.toml](../../rust/Cargo.toml)，包含两个独立 crate，均以 `max-page-size=16384` 编译以满足 Android 15+/16/17 的 **16 KB 页大小对齐** 要求。

### 3.1 `dshn-core` → `app/src/main/jniLibs/arm64-v8a/libdshn_core.so`（`2.16 MB`）

| 模块 | 职责 | 关键实现 |
|---|---|---|
| [extractor.rs](../../rust/dshn-core/src/extractor.rs) | 离线环境高速流式解压 | 直接对 `base.apk` 未压缩 `.bin` 资产切片做 `File` 偏移读取（免二次拷贝），单趟完成 SHA-256 校验、`flate2`/`zlib-rs` 或 `ruzstd` 解压、父目录缓存去重及原生 POSIX `chmod`/`symlink` |
| [fast_chat.rs](../../rust/dshn-core/src/fast_chat.rs) | 容器冷启期间极速直连对话 | `ureq` + `rustls` + `ring`（ARMv8 硬件加密指令）直连 DeepSeek API，零拷贝 SSE 行解析实时回调 Kotlin；容器一旦就绪无缝切回完整 DSH ACP |
| [frame.rs](../../rust/dshn-core/src/frame.rs) | 后台虚拟屏零拷贝帧编码 | JNI 直接读取 `ImageReader` 的 `DirectByteBuffer`，计算 64-bit FNV-1a 哈希；帧未变化时返回 `null`（0 拷贝、0 编码、0 传输）；变化时双线性缩放并用 SIMD JPEG 编码输出 |
| [lib.rs](../../rust/dshn-core/src/lib.rs) | JNI 导出桥 | `DshnNativeCore.kt` 统一封装；支持 `System.getProperty("dshn.native.lib.dir")` 显式 `System.load()`，使独立 `app_process` 也能直接加载 `libdshn_core.so` |

> **说明**：`fast-chat` 设为 Cargo 默认 feature，宿主机纯 Rust 单测可使用 `cargo test -p dshn-core --lib --no-default-features`（无需宿主机 C 编译器），Android 交叉编译时通过 NDK r27c `clang` 全量启用 `fast-chat` + `ring`。

### 3.2 `dshn-mcp-relay` → `app/src/main/jniLibs/arm64-v8a/libdshn_mcp_relay.so`（`393 KB`）

- 编译目标为 `aarch64-unknown-linux-musl` 静态可执行文件，`EnvManager.ensureRuntimeAssets()` 会以内容比对 + 原子替换同步到容器内 `/opt/dshn/dshn-mcp-relay`。
- 取代原先每个 ACP 会话启动一个完整 Node 24 进程的 `dshn-mcp-relay.mjs`（保留 `.mjs` 作为兜底）。
- **关键修复（宿主机 UDS 直连）**：由于静态 musl 二进制不走动态链接器，`bxroot` 的 `LD_PRELOAD` 不会将 `/run/dshn-bridge/device-mcp.sock` 翻译为宿主机路径。`RuntimeSupervisor` 注入 `DSHN_DEVICE_MCP_HOST_SOCK=/data/data/dev.dshn.app/files/bridge/device-mcp.sock`，[rust/dshn-mcp-relay/src/main.rs](../../rust/dshn-mcp-relay/src/main.rs) 优先直连宿主机 UDS 路径，再回退到 `DSHN_DEVICE_MCP_SOCK`。

---

## 4. Android 14+–17 后台虚拟屏架构

### 4.1 绕过厂商限制的原理

OriginOS（vivo / iQOO）禁用了 `settings put global overlay_display_devices`。DSHN 借鉴 MIT 开源项目 [DSH Sugar](https://github.com/xiaoxiao44443/DSH-Sugar) 的思路，通过权限通道（已授权 Root `su` 或无线 ADB `shell,v2,raw:`）以 `uid 0` / `uid 2000 (shell)` 启动独立 `app_process` 进程 [VirtualDisplayAgent.java](../../core/bridge/src/main/java/dev/dshn/app/bridge/virtualdisplay/VirtualDisplayAgent.java)：

1. 直接调用 `DisplayManager.createVirtualDisplay("DSHN Virtual", width, height, dpi, imageReader.getSurface(), flags, ...)`，附加 `SUPPORTS_TOUCH | TRUSTED | OWN_DISPLAY_GROUP | OWN_FOCUS` 标志创建真正受系统信任的独立虚拟屏。
2. 帧采集接入 `ImageReader` + Rust `libdshn_core.so` 零拷贝编码 + FNV-1a 脏帧跳过。
3. 界面树读取通过 `UiAutomation` 连接并传入 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，避免干扰用户现有的无障碍服务。
4. 触控注入直接使用 Android 14+ `InputManager.injectInputEvent`（指定 `displayId`）实现毫秒级后台定向点击与滑动，完全不打扰主屏。

### 4.2 协议、MCP 工具与 UI 交互

- **行协议**：`VirtualDisplayAgent` 与 [VirtualDisplayController.kt](../../core/bridge/src/main/java/dev/dshn/app/bridge/VirtualDisplayController.kt) 通过 `DSHN_VD:{...}` JSON 行通信，支持 `open` / `launch` / `observe` / `tap` / `swipe` / `input` / `key` / `close`。
- **MCP 工具**：[DeviceBridgeMcpServer.kt](../../core/bridge/src/main/java/dev/dshn/app/bridge/DeviceBridgeMcpServer.kt) 新增 `device_virtual_display_open` 与 `device_virtual_display_action`，且原有 `device_screenshot` / `device_screen_tree` / `device_tap` / `device_swipe` / `device_input` 均支持传入 `display_id` 自动路由至后台虚拟屏。
- **无线 ADB 自动回环兜底**：未手动连接无线 ADB 端点时，自动探测 `127.0.0.1:5555`（并给出清晰中文排障指引）。
- **无饥饿实时预览**：[DeviceBridgeScreen.kt](../../feature/bridge/src/main/java/dev/dshn/feature/bridge/DeviceBridgeScreen.kt) 内的实时预览卡片通过 `suspend` 串行轮询 `observeIfIdle()`（内部使用 `opMutex.tryLock()` + `wait_frame_ms = 0`），保证预览刷新永远不会排队阻塞 `launchApp` / `tap` / `sendKey` / `close`。

---

## 5. 启动、打包与运行时优化

| 优化项 | 位置 | 效果 |
|---|---|---|
| **R8 裁剪与资源收缩** | `app/build.gradle.kts` + `app/proguard-rules.pro` + `profileinstaller:1.4.1` | 新增 `stagingRelease`（默认用 debug keystore 方便本地装机测试）与 `release` 构建类型；DEX 从 13 个 `54.71 MB` 降至 **1 个 `3.02 MB`（-94.5%）**，APK 从 `179.99 MB` 降至 **`120.10 MB`（-60 MB）** |
| **Node 24 V8 编译缓存** | `ContainerLauncher.kt` | 启用 `NODE_COMPILE_CACHE=/root/.cache/dshn-v8`，内置 DSH Sugar 记录的 `pwritev` 零字节缓存自愈保护（检测到 ≥5 个 0 字节文件自动禁用） |
| **DSH `profile-resolution` 加速** | `EnvManager.kt` | 幂等应用 MIT 许可的 DSH Sugar 补丁，非 linked 安装场景跳过冗余 `lstat`/`realpathSync` |
| **按需挂载 MCP Relay** | `RuntimeSupervisor.kt` | `bridgeEnabled == false` 时不挂载设备桥 MCP 进程，减少容器启动和常驻开销 |
| **主线程瘦身与流式聚合** | `AppContainer.kt`、`RuntimeSessionCoordinator.kt`、`EnvManager.kt` | 文件/Socket 初始化移出主线程；资源无变更时跳过冗余 `fsync`；`waitForHealthyRuntime()` 用 `StateFlow.first` 替换 `250 ms` 轮询；流式文本 `40 ms` 批量刷新（首字零延迟） |
| **DSH `0.2.2-rc.5` 凭据迁移** | `EnvManager.kt` | 幂等将旧版扁平 `/root/.dsh/.credentials.yaml`（及 `/root/.dsh-terminal/.credentials.yaml`）迁移为 `version: 1` + `refs:` 结构，兼容 BOM、`---` 标记、嵌套 `version:`、单行 `{...}` 与 `0600` 权限 |

---

## 6. 双机真机实测数据

### 6.1 Android 17 旗舰机：`vivo V2618A`（SM8850，Android 17 / API 37，1440×3200）

| 指标 | 旧版 `v0.3.2`（同机实测） | 新版 `v0.4.0-perf`（`acb7a8b` 实测） | 变化 |
|---|---|---|---|
| **冷启动 `MainActivity`** | `471 ms`（首冷）/ `258 ms`（次冷） | **`95 ms` / `89 ms` / `95 ms`** | **进入 100 ms 内（快 ~3×–5×）** |
| **主进程内存 `TOTAL PSS`** | `126.8 MB`（RSS `300.8 MB`） | **`88.7 MB`**（RSS `237.6 MB`） | **-38.1 MB（-30%）** |
| **MCP Relay 进程** | 额外 Node 24 V8（`dshn-mcp-relay.mjs`） | **393 KB 静态 Rust `dshn-mcp-relay`** | 消除额外 V8 进程 |
| **完整 `DSH 0.2.2-rc.5` ACP 就绪** | `3.50 s` | **`2.99 s`**（且支持 `~0.25 s` 极速直连） | 更快且开屏可聊 |
| **运行日志（`日志与诊断`）** | 含旧版中继/凭据报错 | **仅 4 行，0 报错、0 警告** | `[acp] initialize ok` + `session-bridge.sock healthy` |
| **真实 Agent 端到端** | — | **8/8 工具绿勾通过**（7× `bash` + 1× `mcp__dshn-device-bridge__device_info`） | 跨容器静态 Rust MCP 正常读回本机参数 |

**Android 17（API 37）后台虚拟屏实测（`720×1280 @300dpi`）**：
- `OPEN` 建屏：**`37.0 ms`**（`display_id=6`，`rust_encoder=true`，`ui_tree_available=true`，`direct_touch_available=true`）
- `EMPTY_POLL (wait_frame_ms=0)`：**`3.3 ms`**（无锁饥饿）
- `LAUNCH com.android.settings`：**`164.4 ms`**（后台打开 `SettingsHomepageActivity`）
- `OBSERVE #1`（Rust SIMD JPEG + 界面树）：**`39.9 ms`**（`58.1 KB` 清晰暗色设置页 + **42 个 UI 节点**）
- `OBSERVE #2`（FNV-1a 脏帧跳过）：**`3.7 ms`**（`unchanged=true`，`0 B` 图像回传）
- `TAP` 直接触控注入：**`40.7 ms`**（`direct: true`）；`CLOSE`：**`22.8 ms`**

### 6.2 Android 16 实机：`vivo V2352A`（iQOO Z9 Turbo，Android 16 / API 36）

| 指标 | 旧版 Debug | 新版 `v0.4.0-perf`（R8 `stagingRelease`） | 变化 |
|---|---|---|---|
| **APK 体积** | `179.99 MB` | **`120.10 MB`**（去除 `114.83 MB` 离线环境后核心仅 `5.2 MB`） | **-60 MB** |
| **DEX 体积与数量** | `13 DEX` / `54.71 MB` | **`1 DEX` / `3.02 MB`** | **-94.5%** |
| **冷启动 `MainActivity`** | `522 ms`（中位） | **`237 ms`–`256 ms`** | **>2× 提速** |
| **主进程内存 `TOTAL PSS`** | — | **`73.4 MB`**（Dalvik Heap `3.2 MB`；`:runtime` `9.5 MB`） | 极低常驻内存 |
| **极速直连可用时间** | 需等容器 | **`0.25 s`**（`libdshn_core.so` 在 `+126 ms` 加载完成） | 几乎零等待 |
| **后台虚拟屏** | `overlay_display_devices` 被禁用 | **`Display #8` / `#11` 正常运行**（Rust JPEG `78 ms` / 脏帧跳过 `0.0 ms` / 41 UI 节点 / 应用内一键通过 `127.0.0.1:5555` 拉起） | 绕过 OriginOS 限制 |

---

## 7. 构建与复现指南

### 7.1 重新编译 Rust 原生库（可选；预编译产物已随仓库检入）

环境要求：Rust `1.100+`（安装 `aarch64-linux-android` 与 `aarch64-unknown-linux-musl` target）+ Android NDK r27c（`27.2.12479018`）。

```powershell
# Windows 路径若包含中文用户名，务必使用纯 ASCII 路径存放 Rust/NDK/Gradle 临时目录
$env:ANDROID_NDK_ROOT = "C:\gemini\.android-tools\ndk\27.2.12479018"
$env:CARGO_TARGET_DIR = "C:\gemini\.gradle-tmp\dshn-rust-target"
powershell -NoProfile -ExecutionPolicy Bypass -File .\rust\build-android.ps1
```

产物自动输出至：
- `app/src/main/jniLibs/arm64-v8a/libdshn_core.so`
- `app/src/main/jniLibs/arm64-v8a/libdshn_mcp_relay.so`

### 7.2 单元测试与 APK 构建

```bash
# 1. Rust 宿主单元测试
cargo test --manifest-path rust/Cargo.toml -p dshn-core --lib --no-default-features

# 2. Android JVM 单元测试 + R8 stagingRelease 打包
./gradlew testDebugUnitTest :app:assembleStagingRelease
# 产物: app/build/outputs/apk/stagingRelease/app-stagingRelease.apk

# 3. 开发期快速迭代（跳过 115 MB 离线环境资产打包，APK 约 5 MB）
./gradlew :app:assembleStagingRelease -PdshnSkipBundledEnv=true
```

> **Windows 中文用户名构建注意**：`aapt2.exe` 与 NDK `clang.exe` 遇到非 ASCII 临时目录可能异常退出（`0xc0000142`），需传入 ASCII 路径的 `GRADLE_OPTS="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/gemini/.gradle-tmp"` 与 `-Pandroid.aapt2FromMavenOverride=<ASCII路径>/aapt2.exe`。

---

## 8. 真机踩坑记录

| 问题 | 根因 | 解决方案 |
|---|---|---|
| 静态 Rust `dshn-mcp-relay` 报 `os error 2` 找不到 `/run/dshn-bridge/device-mcp.sock` | 静态 musl 程序无动态链接器，`bxroot` 的 `LD_PRELOAD` 不会改写路径 | `RuntimeSupervisor` 注入宿主机绝对路径 `DSHN_DEVICE_MCP_HOST_SOCK`，中继优先直连 |
| `VirtualDisplayAgent` 在 `app_process` 中 `rust_encoder=false` | `app_process` 默认 `java.library.path` 不含 App `lib/arm64` | 启动时传 `--native-lib-dir`，`DshnNativeCore` 读取 `dshn.native.lib.dir` 调用 `System.load(absPath)` |
| 应用内点「启动虚拟屏」后点「打开应用」无响应 | `DeviceBridgeScreen` 每 `450 ms` fire-and-forget 轮询 `observe()`，空屏时每次阻塞 `900 ms` 导致 `opMutex` 排队饿死用户操作 | 新增 `wait_frame_ms=0` 与非阻塞 `observeIfIdle()`（`opMutex.tryLock()`），UI 改为 `suspend` 串行轮询 |
| `DSH 0.2.2-rc.5` 启动报 `.credentials.yaml uses the pre-release flat layout` | 旧版扁平格式缺少 `version: 1` 与 `refs:`；首次迁移判断未屏蔽缩进内 `version:` 与前导 `---` | `EnvManager.migrateLegacyCredentialsYamlText` 改为仅检测顶层无缩进键，兼容 BOM / `---` / `{...}` / 缺失 `version: 1`，并保持 `0600` 权限 |
| Windows 宿主机跑 `cargo test` 编译 `ring` 缺少 `gcc.exe` | 宿主环境仅装有 NDK `clang` 用于 Android 交叉编译 | 将 `fast-chat` 设为默认 feature，宿主机单测加 `--no-default-features` |

---

## 9. 分支说明与开源致谢

- 本分支 **`feat/rust-perf-engine`** 基于 `aistudio-refactor`（模块化 11 子模块架构）演进，**原样保留** `main`、`aistudio-refactor` 及 `bxroot` 运行时。
- 开源组件与许可证详情见 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)（特别致谢 MIT 开源项目 [DSH Sugar](https://github.com/xiaoxiao44443/DSH-Sugar) 的 `profile-resolution` 加速思路与 Android 14+ 虚拟屏参考设计）。
