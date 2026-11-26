# DSHN — DeepSeek Harness Native for Android

English | [中文说明](README.zh.md)

DSHN wraps [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/dsh) in a native Android Material 3 application powered by [`bxroot`](https://github.com/qiannianhuanxiang/bxroot) (`arm64-v8a`), Node.js 24, and Ubuntu 24.04 user-space runtime — keeping DSH sources unpatched while exposing Android UI, session history, an interactive PTY terminal, and an opt-in Device Bridge MCP server.

## Architecture

- **Rust native performance core (`feat/rust-perf-engine` / `v0.4.0-perf`)**:
  - `libdshn_core.so` (16 KB page-aligned AArch64 JNI, 2.16 MB): single-pass zero-copy `.tar.gz`/`.tar.zst` APK slice extraction with hardware SHA-256, native `rustls` + `ring` SSE Fast-Chat (~0.25 s instant replies while the container boots), and zero-copy RGBA `DirectByteBuffer` → FNV-1a dirty-frame skip → SIMD JPEG encoding for background virtual displays.
  - `libdshn_mcp_relay.so` (393 KB static `aarch64-unknown-linux-musl`): replaces spawning a second Node 24 V8 process per ACP session and connects directly to the host UDS socket.
  - R8 release shrinking (`stagingRelease` / `release`) cuts DEX size by 94.5% and reaches **~95 ms** cold start on Android 17 (SM8850) with 30% lower PSS memory. See [docs/architecture/rust-perf-and-virtual-display.md](docs/architecture/rust-perf-and-virtual-display.md) for architecture details and benchmarks.
- **Android 14+–17 Background Virtual Display**: Launches a privileged `VirtualDisplayAgent` via opt-in Root (`su`) or Wireless ADB (`shell,v2,raw:`) using `DisplayManager.createVirtualDisplay`, `UiAutomation`, `InputManager`, and Rust JPEG encoding — bypassing OEM blocks on `overlay_display_devices` without disturbing the main screen.
- **Two-process supervision**:
  - `:app` hosts the Material 3 Compose UI (`Chat`, `Sessions`, `Terminal`, `Lab`, `Settings`), AccessibilityService, key management (`AndroidKeyStore`), `RootAuthManager` (opt-in standard Android `su`), `VirtualDisplayController`, and `DeviceBridgeMcpServer` (`files/bridge/device-mcp.sock`).
  - `:runtime` (`RuntimeService`, `foregroundServiceType="specialUse"`) supervises `libbxroot.so` running `dsh --profile acp --patch /opt/dshn/dshn-bridge.patch.yml` (adapted for `@deepseek-ai/dsh@0.2.2-rc.5` with `NODE_COMPILE_CACHE`, MIT DSH Sugar `profile-resolution` fast-path, and idempotent `.credentials.yaml` migration) with single-writer `FileLock`, stderr ring logging, and bounded crash recovery.
- **Zero DSH source patches**:
  - History listing, surface projection replay, slash commands, and `ask_user_question` attach cleanly via `--patch` (`dshn-session-bridge.mjs`) over `/run/dshn-bridge/session-bridge.sock`.
  - Android device tools mount on-demand via ACP `mcpServers` using static Rust `/opt/dshn/dshn-mcp-relay` (falling back to `dshn-mcp-relay.mjs`).

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
