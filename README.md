# DSHN — DeepSeek Harness Native for Android

English | [中文说明](README.zh.md)

DSHN wraps [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/dsh) in a native Android Material 3 application powered by [`bxroot`](https://github.com/qiannianhuanxiang/bxroot) (`arm64-v8a`), Node.js 24, and Ubuntu 24.04 user-space runtime — keeping DSH sources unpatched while exposing Android UI, session history, an interactive PTY terminal, and an opt-in Device Bridge MCP server.

## Architecture

- **Two-process supervision**:
  - `:app` hosts the Material 3 Compose UI (`Chat`, `Sessions`, `Terminal`, `Settings`), AccessibilityService, key management (`AndroidKeyStore`), and `DeviceBridgeMcpServer` (`files/bridge/device-mcp.sock`).
  - `:runtime` (`RuntimeService`, `foregroundServiceType="specialUse"`) supervises `libbxroot.so` running `dsh --profile acp --patch /opt/dshn/dshn-bridge.patch.yml` with single-writer `FileLock`, stderr ring logging, and bounded crash recovery.
- **Zero DSH source patches**:
  - History listing, surface projection replay, slash commands, and `ask_user_question` attach cleanly via `--patch` (`dshn-session-bridge.mjs`) over `/run/dshn-bridge/session-bridge.sock`.
  - Android device tools mount cleanly via ACP `mcpServers` stdio relay (`dshn-mcp-relay.mjs`).

## Security & Sandbox Model

- `bxroot` is a user-space compatibility layer, **not a security sandbox**.
- Minimal bind set: only `/dev`, `/proc`, `/sys`, `files/bridge:/run/dshn-bridge` (`0700`), and an explicit optional workspace bind — `/sdcard` and `files/security` are never bound into the container.
- All Device Bridge capabilities default **OFF** and require per-call confirmation for mutating UI actions or allowlisted Wireless ADB `argv` commands (`[POLICY_BLOCKED]` outside the allowlist).
- On Android kernels lacking Landlock (e.g., `6.1.145` on tested Android 16 hardware), `bash` requires setting Execution Permission Tier to `Full Access (Bash Enabled)`, while `Workspace Write` and `Read Only` protect file operations via `fs-sandbox` + interactive approval.

## Building & Testing

Requirements: JDK 21, Android SDK Platform 36 (`compileSdk 36`, `minSdk 30`, `arm64-v8a`).

```bash
./gradlew testDebugUnitTest assembleDebug
```

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for vendored binaries and GPL written-offer notices, and [CHANGELOG.md](CHANGELOG.md) for release notes.
