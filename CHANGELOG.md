# Changelog

## 0.1.0 (2026-11-13)

### Added
- **Isolated `:runtime` Supervisor Process & `specialUse` ForegroundService**:
  - `RuntimeService` / `RuntimeSupervisor` running `dsh --profile acp --patch /opt/dshn/dshn-bridge.patch.yml` inside `bxroot`.
  - Single-writer `FileLock` + stale PID cleanup, continuous stderr ring log (`files/logs/runtime.log`), 5-second watchdog, bounded crash-loop restart (max 3 restarts in 60s), stdout corruption detection (`DEGRADED` state), and turn-active `PARTIAL_WAKE_LOCK`.
  - Interrupted-turn `TOOL_OUTCOME_UNKNOWN` reporting with retry affordance.
- **Insert-Only Session & Interactive Bridges (`assets/dshn/`)**:
  - `dshn-session-bridge.mjs` exposes `sessions.list`, `sessions.surface` (preserving corrupt files strictly read-only), `commands.list`, `commands.execute`, and `user-questions/request` over a private bind-mounted Unix domain socket (`/run/dshn-bridge/session-bridge.sock`).
  - Large Binder payloads (`@file:` transfer) avoid Android 1 MB Binder limits.
- **Device Bridge MCP Server (`dev.dshn.app.bridge`)**:
  - Local filesystem UDS server (`device-mcp.sock`) + zero-dep Node stdio relay (`dshn-mcp-relay.mjs`), mounted into ACP sessions via `mcpServers`.
  - Tools: `device_info`, `device_sensors`, `device_screen_tree` (wrapped in `<untrusted_screen_content>`), `device_screenshot` (API 30+), `device_tap`, `device_swipe`, `device_input`, `device_global_action`, `device_adb_exec`.
  - All bridge toggles default **OFF**, high-risk actions require per-call user confirmation with prompt-injection notice, `AdbArgvPolicy` enforces strict structured `argv` allowlisting returning `[POLICY_BLOCKED]`, and `AuditLogger` logs bounded JSONL audit events outside the container.
- **Multi-Tab Jetpack Compose Material 3 UI**:
  - **Chat**: ACP streaming thought/answer/tool cards, dynamic `configOptions` model/reasoning picker, context usage counter, slash command autocomplete, approval & interactive `ask_user_question` dialogs.
  - **Sessions**: List live/persisted/corrupt sessions, create with cwd, switch/resume with surface history fold replay, close with occupied protection.
  - **Terminal**: Real `node-pty` shell with ANSI VT buffer renderer, extra keys (`ESC`, `TAB`, `CTRL`, arrows, `:wq`), raw keystroke mode, and isolated `DSH_HOME=/root/.dsh-terminal`.
  - **Settings & Transactions**: AndroidKeyStore-encrypted API key storage (with same-UID container boundary disclosure), execution permission tiers, Device Bridge & NSD Wireless ADB controls, Android 13+ restricted settings & Phantom Process Killer guides, transactional `~/.dsh` snapshots with rollback schema check, container self-test, and sanitized diagnostics export.
- **OSS & CI**:
  - MIT `LICENSE`, `THIRD_PARTY_NOTICES.md`, bilingual `README.md` / `README.zh.md`, JVM unit tests, and GitHub Actions CI.
