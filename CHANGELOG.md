# Changelog

## 0.3.2 (2026-11-20)

### Added
- **Bundled runtime environment**: Ubuntu 24.04.5, Node.js 24.22.3 and DSH 0.2.2-rc.5 ship inside the APK (`assets/env/`) and are unpacked on first launch by `TarGzExtractor` / `EnvManager.installBundledEnvironment`, with progress shown in the setup wizard, chat and Settings › Runtime (install, retry, repair/reinstall). No computer is needed for first-time setup any more.

### Fixed — interaction logic (multi-agent review, verified fixes)
- Streaming, approvals and questions are tracked per chat: switching chats mid-reply no longer leaves sending stuck, background approvals are queued and announced, and a chat re-opened mid-reply reloads the full persisted turn when it finishes.
- Session operations (start, restart, stop, new, switch, reattach, close) are serialized; closing a chat blocks sending until it finishes, and a prompt is never dispatched into a chat that was closed or switched meanwhile.
- The bundled-environment installer is single-flight; restart, terminal and sending are blocked with an explanation while it runs.
- Setup wizard: progress survives process death, Back on a re-opened wizard returns to the app, changing the execution tier while running still asks for a restart.
- "Regenerate" replaces the previous answer instead of appending a second one; failed turns offer a retry banner; send explains why it is unavailable (loading, starting, stopping, installing).
- Session list: stale refreshes are dropped, "live" dots clear when the runtime stops, non-fatal read warnings are shown under the session.
- Snapshot restore treats a DEGRADED runtime as running.
- Empty-chat 2×2 suggestion cards now enforce uniform height (`IntrinsicSize.Max` + `fillMaxHeight` + `minLines = 2` subtitle) so cards stay aligned when subtitles wrap; composer config chips gain spacing before the send button and tighter chip padding so model & effort chips fit cleanly without clipping.

## 0.3.0 (2026-11-19)

### Changed — UI & interaction redesign
- **Chat-first navigation**: the five-tab bottom bar is replaced by a chat home screen with a left drawer (new chat, new chat in a folder, search, sessions grouped by day with per-row menu, shortcuts to Terminal / Device bridge / Settings and a runtime status line). Secondary screens use a back stack with animated transitions and system-back support; per-screen state is preserved.
- **First-run setup wizard** (`SetupScreen`): welcome → runtime environment check (with step-by-step rootfs preparation guide) → DeepSeek API key → execution tier → background permissions → summary. Existing configured installs skip it automatically; it can be re-run from Settings.
- **Chat screen**: status-aware top bar (runtime state, model), contextual banners (missing key, environment not ready, starting, stopped, error with log shortcut, degraded, read-only, interrupted turn), empty-state suggestion cards, Markdown rendering (code blocks with copy, lists, tables, quotes, links), collapsible thinking block, compact tool-call rows with status, copy / edit-and-resend / regenerate actions, auto-follow scrolling with a jump-to-bottom button, rounded composer with model and reasoning chips (bottom sheet picker), context-usage ring and slash-command suggestions. "New chat" reuses the current chat while it is still empty.
- **Settings** reorganized into grouped pages: Model & API key, Permissions & workspace (default folder for new chats), Background, Runtime environment (component checks, start/restart/stop, self-test, DSH update with confirmation, snapshots with restore confirmation), Logs & diagnostics, About.
- **Device bridge & Root** consolidated into one page with a master switch, dependent capability switches, confirmation before disabling per-call confirmation or enabling custom Root commands, accessibility / wireless ADB / Root authorization sections and a recent-activity list; the old Lab remains as an advanced "调试台".
- All new UI copy is bilingual through `tr(zh, en)` / `t(zh, en)`; refreshed DeepSeek-blue light and dark palettes and typography.
- **App icon**: new adaptive launcher icon (chat bubble with terminal prompt and spark) including a monochrome layer for themed icons, plus a matching in-app brand mark and splash background.

### Removed
- `SessionsScreen` (merged into the chat drawer).

## 0.2.0 (2026-11-19)

### Added
- **Opt-In Android Root Authorization (`su`) Subsystem**:
  - `RootAuthManager` (`dev.dshn.app.bridge`): detects standard Android `su` binaries across Magisk / KernelSU / SukiSU / APatch / custom paths, handles explicit user-consented authorization (`su -c id && getenforce`), tracks SELinux status & configurable session TTL (15m / 30m / 1h / manual revoke), and supports immediate revocation.
  - `RootCommandPolicy` (`dev.dshn.app.security`): dual-mode structured `argv` policy (`ALLOWLIST_ONLY` default + opt-in `CUSTOM_WITH_CONFIRM` with mandatory per-call confirmation), universal hard-blocks for catastrophic partition/root-fs commands, and strict secret isolation forbidding access to `dev.dshn.app/files/security/` (`secrets.enc`, `adb_identity.enc`, `audit.jsonl`) in all modes.
  - `DeviceBridgeMcpServer` (`0.2.0`): added fixed-catalog MCP tools `device_root_status` and `device_root_exec` (default OFF, 6-gate authorization + high-visibility red `ROOT (uid=0)` confirmation dialog + `audit.jsonl` logging).
  - `HostRootShellSession` & `TerminalScreen`: optional `Host Root (su)` shell mode alongside isolated Ubuntu Container PTY, gated behind active Root authorization and tagged with a red `HOST ROOT` pill.
  - `SettingsScreen` & `DeviceLabScreen`: full Root Authorization management card, policy/TTL selectors, one-click Root Phantom Process Killer fix (`device_config put activity_manager max_phantom_processes 2147483647`), and interactive Root policy trial bench.
- **DSH `0.2.2-rc.5` Adaptation**:
  - Updated target DSH baseline to `@deepseek-ai/dsh@0.2.2-rc.5` (Node `>= 24.9.0` engine check, default model `deepseek-flash`).
  - Added transactional in-app DSH upgrade flow (`EnvManager.upgradeDshToVersion`) with automatic pre-upgrade `~/.dsh` snapshot, required `--allow-scripts` installation, and post-upgrade self-test.
  - Added `CONTENT_REFUSED` refusal classification (`0.2.2-rc.3+`) across `AcpClient`, `RuntimeSupervisor`, `SurfaceHistoryFold`, and `MainViewModel`.
  - Added custom `DEEPSEEK_BASE_URL`, `DEEPSEEK_SEARCH_BASE_URL`, and `DSH_WEB_SEARCH_PROVIDER` (`Auto` / `deepseek-official` / `deepseek-search-api`) environment controls (`0.2.2-rc.4+`).
  - Added ACP `agentCapabilities` parsing (`promptCapabilities.image`, `httpMcp`, `sessionCapabilities`), multimodal `PromptBlock` support, clean `tool_call_update` text extraction, `retry` user source & image block history folding, and `session.vN.jsonl.zstd` rollback compatibility checks.
  - Updated `dshn-session-bridge.mjs` (`origin` / `agentPreset` metadata, non-subagent `findAgent`, `hasAgentTeam` / `hasAttachments` ping fields) and `dshn-pty-runner.mjs` (resilient multi-path `node-pty` resolution).

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
