# DSHN — DeepSeek Harness Native for Android

English | [中文说明](README.zh.md)

DSHN wraps [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/dsh) in a native Android Material 3 application powered by a Rust native performance core, [`bxroot`](https://github.com/qiannianhuanxiang/bxroot) (`arm64-v8a`), Node.js `24.22.3`, and a bundled Ubuntu `24.04.5` (ACP Slim) user-space runtime — keeping all functional integrations strictly outside upstream DSH source (ACP, insert-only `--patch`, and MCP, with only one optional idempotent MIT `profile-resolution` boot fast-path) while providing native chat UX, plugin management (including local archive import), live context & prompt cache-hit inspection, session history, an interactive PTY terminal, and an opt-in Device Bridge MCP server with selectable-resolution Android 14+–17 background virtual displays.

---

## What Does `perf` Mean? (`v0.4.0-perf` / `feat/rust-perf-engine`)

**`perf`** stands for **Performance** (not the Linux `perf` profiler). It designates the performance-focused generation of DSHN built on `feat/rust-perf-engine` (`0.4.0-perf`):

1. **Rust native acceleration**: Moves the four heaviest mobile hot paths out of JVM/Node into 16 KB page-aligned AArch64 Rust binaries — zero-copy `.tar.gz`/`.tar.zst` APK extraction with hardware SHA-256, `rustls` + `ring` instant Fast-Chat during container cold start, zero-copy RGBA→JPEG virtual display encoding with FNV-1a dirty-frame skipping, and a 393 KB static `musl` MCP UDS relay (eliminating an extra Node 24 V8 process per session).
2. **R8 release shrinking**: Cuts DEX from 13 files (`54.71 MB`) down to **1 DEX (`3.02 MB`, -94.5%)** and shrinks APK size from `180 MB` to **`120 MB`** (~`5.2 MB` app core excluding the `115 MB` bundled offline runtime assets).
3. **Cold-start & runtime tuning**: Offloads disk/socket init from the main thread, enables `NODE_COMPILE_CACHE` with zero-byte `pwritev` self-healing, applies the MIT DSH Sugar `profile-resolution` fast-path, mounts Device Bridge MCP on demand, and coalesces streaming updates on a 40 ms window — reaching **~95 ms** cold start and **-30% PSS memory** on Android 17 (SM8850).

---

## Hybrid Dual-Layer Architecture

DSHN separates native Android workloads from the full Linux/Node agent runtime across **two isolated Android processes** (`:app` and `:runtime`) and **11 Gradle modules** (`:app` + 5 `:core:*` + 5 `:feature:*`):

```text
┌──────────────────── :app process (Native Android Bionic) ────────────────────┐
│  Jetpack Compose Material 3 UI (Chat / Context Inspector / Plugins / Shell)  │
│  ├─ libdshn_core.so (Rust JNI, Bionic, 16KB-aligned, 2.16 MB, zero-overhead) │
│  │    ├─ extractor.rs : zero-copy APK slice tar.gz/zst + hardware SHA-256    │
│  │    ├─ fast_chat.rs : rustls/ring SSE Fast-Chat (~0.25 s while booting)    │
│  │    └─ frame.rs     : DirectByteBuffer → FNV-1a dirty-skip → SIMD JPEG     │
│  ├─ DeviceBridgeMcpServer (private UDS: files/bridge/device-mcp.sock, 0600)  │
│  └─ VirtualDisplayController (opt-in Root su or Wireless ADB shell,v2,raw)   │
└──────────────────────────┬───────────────────────────┬───────────────────────┘
                           │ AIDL + Unix Socket        │ su / ADB shell,v2,raw
                           ▼                           ▼
┌───────── :runtime process (bxroot + Ubuntu 24.04 glibc) ────────┐ ┌─ app_process ─┐
│  specialUse ForegroundService — isolates crashes from UI        │ │ VirtualDisplay│
│  ├─ Native ARM64 execution (zero ISA translation)               │ │ DisplayManager│
│  ├─ bxroot LD_PRELOAD path mapping (far lighter than proot)     │ │ ImageReader   │
│  ├─ Node 24 + DSH 0.2.2-rc.5 (`dsh --profile acp --patch ...`)  │ │ UiAutomation  │
│  ├─ dshn-session-bridge.mjs (--patch plugin, zero DSH edits):   │ │ InputManager  │
│  │    sessions, context/cache inspection, plugin manager, HITL  │ └───────────────┘
│  └─ /opt/dshn/dshn-mcp-relay (393 KB static musl Rust binary)   │
│       └─ connects directly to host device-mcp.sock              │
└─────────────────────────────────────────────────────────────────┘
```

- **Native Android Bionic layer**: Compose UI, Rust JNI core (`libdshn_core.so`), and virtual display agent execute directly on Android Bionic with **zero translation and zero wrapper overhead**.
- **User-space Ubuntu glibc container (`bxroot`)**: Runs stock ARM64 Node.js 24, `node-pty`, and `@deepseek-ai/dsh@0.2.2-rc.5` with **zero CPU instruction translation**; only filesystem path interception passes through `bxroot`'s lightweight `LD_PRELOAD` hooks (avoiding `proot`'s heavy `ptrace` syscall traps).
- **Static `musl` relay**: `dshn-mcp-relay` has zero libc dependency and connects directly to `DSHN_DEVICE_MCP_HOST_SOCK`, eliminating a redundant Node V8 runtime per chat session.

---

## Key Features

- **Native Chat, Model Picker & Live Context Inspector**:
  - Compact single-line model & reasoning-effort picker (`ModelPickerMenu`).
  - Interactive composer context ring (`ContextInspectorMenu`): tapping the percentage ring opens a live popup querying `dshn-session-bridge.mjs` (`context.inspect`) for context-window usage, token breakdown (`system` / `tools` / `messages`), **prompt cache hit rate** (`cacheReadTokens / (uncachedInputTokens + cacheReadTokens + cacheWriteTokens)` + last-turn hit rate), expandable/copyable **System Prompt** sections & dynamic contexts, registered **Tools** (built-in / MCP), and discovered **Skills**.
- **DSH Plugin Manager (`PluginsScreen`)**:
  - Accessible from both the chat drawer and Settings → Agent → Plugins, backed directly by DSH's `pluginManager` service (`plugins.list`, `setEnabled`, `install`, `remove`, `installPnpm`).
  - Toggle all 16 shipped official optional bundles offline, install/remove npm or GitHub bundles, or **import local plugin packages** (`.tgz` / `.tar.gz` / `.zip` via Android SAF with Zip-Slip protection, or container paths), with one-tap container `pnpm` bootstrap when missing and guided runtime restart when ACP HMR is disabled.
- **Android 14+–17 Background Virtual Display**:
  - Launches privileged `VirtualDisplayAgent` via opt-in Root (`su`) or Wireless ADB (`shell,v2,raw:`) using `DisplayManager.createVirtualDisplay`, `UiAutomation`, `InputManager`, and Rust JPEG encoding — bypassing OEM blocks on `overlay_display_devices` without disturbing the main screen, with 5 persisted resolution presets (`540×960` / `720×1280` / `900×1600` / `1080×1920` / `1280×720` landscape).
- **Lossless Fast-Chat → ACP Handoff & Transactional Runtime**:
  - Cold-start Rust Fast-Chat turns are packed into the first DSH ACP prompt (`FastChatHandoff`) so DSH keeps full context and persists the full transcript in its own session log (unpacked seamlessly on history reload by `SurfaceHistoryFold`).
  - Bundled offline Ubuntu `24.04.5` + Node `24.22.3` + DSH `0.2.2-rc.5` (ACP Slim) assets unpack transactionally on first launch with SHA-256 verification, idempotent `.credentials.yaml` migration, and snapshot/rollback support.

See [docs/architecture/rust-perf-and-virtual-display.md](docs/architecture/rust-perf-and-virtual-display.md) and [docs/architecture/modularization.md](docs/architecture/modularization.md) for architecture details and dual-device benchmarks.

---

## Security & Sandbox Model

- `bxroot` is a user-space compatibility layer, **not a security sandbox**.
- Minimal bind set: only `/dev`, `/proc`, `/sys`, `files/bridge:/run/dshn-bridge` (`0700`), and an explicit optional workspace bind — `/sdcard` and `files/security` are never bound into the container.
- All Device Bridge capabilities (Accessibility, Background Virtual Display, Wireless ADB, and opt-in Host Root `su`) default **OFF** and require per-call confirmation for mutating UI actions, allowlisted Wireless ADB `argv` commands, or Root `argv` commands (`[POLICY_BLOCKED]` outside policy; DSHN's own `files/security/` directory is hard-blocked in all modes).
- On Android kernels lacking Landlock (e.g., `6.1.145` on tested Android 16 hardware), `bash` requires setting Execution Permission Tier to `Full Access (Bash Enabled)`, while `Workspace Write` and `Read Only` protect file operations via `fs-sandbox` + interactive approval.

## Building & Testing

Requirements: JDK 21, Android SDK Platform 36 (`compileSdk 36`, `minSdk 30`, `arm64-v8a`). Prebuilt Rust `.so` binaries are checked in; rebuilding `rust/` requires Rust (`aarch64-linux-android` + `aarch64-unknown-linux-musl`) and Android NDK r27c.

```bash
./gradlew testDebugUnitTest :app:assembleStagingRelease
# Fast iteration without the 115 MB bundled offline container assets:
./gradlew :app:assembleStagingRelease -PdshnSkipBundledEnv=true
```

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for vendored binaries and GPL written-offer notices, and [CHANGELOG.md](CHANGELOG.md) for release notes.
