# Changelog

## 0.4.0-perf (2026-11-27, feat/rust-perf-engine)

### Added — DSH Plugins Management, Interactive Context Inspector & Cleaner Model Picker
- **DSH Plugin Management (`PluginsScreen`, Drawer + Settings → Agent → Plugins)**:
  - Restored native plugin management backed by DSH `0.2.2-rc.5`'s `ctx.get("pluginManager")` service over `dshn-session-bridge.mjs` (`plugins.list`, `plugins.setEnabled`, `plugins.install`, `plugins.remove`, `plugins.installPnpm`).
  - Supports toggling all shipped official optional bundles offline, installing/removing external npm or GitHub bundles (`dsh plugin --profile acp add <spec>`), **importing local plugin packages** (`.tgz` / `.tar.gz` / `.zip` via Android SAF into `/root/.dsh/local-plugins/` with Zip-Slip protection & single-folder ZIP unwrapping, or container absolute paths), one-tap container `pnpm` bootstrap when absent, and a restart-runtime banner when ACP HMR is disabled.
- **Lossless Fast-Chat → ACP Context Handoff (`FastChatHandoff` + `SurfaceHistoryFold`)**:
  - Cold-start turns sent via Rust `libdshn_core.so` while the Ubuntu/DSH container is booting are now seamlessly carried into the first DSH ACP prompt via a structured handoff envelope (`[DSHN_FAST_CHAT_HANDOFF_V1]`), persisted in DSH's own session log as the single source of truth, and unpacked back into distinct user/assistant turns when reloading history via `SurfaceHistoryFold` (retried safely if the handoff turn errors before replying, and cleared cleanly on session switch/close).
- **CI Coverage Expansion (`.github/workflows/ci.yml`)**:
  - Extended GitHub Actions CI to run on both `main` and `feat/rust-perf-engine`, adding `dshn-dns-preload.cjs` syntax check, Rust unit tests (`cargo test -p dshn-core --lib --no-default-features`), `dshn-mcp-relay` compile check, and R8 `:app:assembleStagingRelease -PdshnSkipBundledEnv=true` verification.
- **Selectable Virtual Display Resolutions (`VirtualDisplayResolutionPreset`)**:
  - Added 5 persisted presets (`540×960` Fast, `720×1280` Balanced, `900×1600` Sharp, `1080×1920` FHD, `1280×720` Landscape) in `SettingsStore`, `DeviceBridgeScreen`, `VirtualDisplayController`, `VirtualDisplayAgent`, and `DeviceBridgeMcpServer` (live-switches resolution cleanly when already running).
- **Fixed Output Truncation & Streaming Performance**:
  - Eliminated end-of-turn chunk truncation race between `AcpClient.updates` and `session/prompt` completion via ordered inline `onSessionUpdate` dispatch on the ACP stdout reader thread + unbounded FIFO `Channel.UNLIMITED` in `RuntimeController` (eliminating `MutableSharedFlow.tryEmit` buffer overflow drops).
  - Routed large `onSessionUpdate` payloads through `LargePayloadXfer` and removed redundant raw `tool_call_update.content` duplication over Binder to prevent silent `TransactionTooLargeException` drops; raised tool output preview limit from `2,400` to `16,000` chars with explicit truncation indicator.
  - Eliminated O(N²) `String.substring(i)` allocations in `MarkdownText.appendInline()` (now O(N) zero-copy via `Regex.matchAt`) and memoized per-block `inlineMarkdown` spans to prevent UI stalls during streaming.
- **Interactive Context Inspector Popup (`ContextInspectorMenu`, Chat Composer)**:
  - Tapping the composer's circular context percentage indicator opens a compact popup (matching `ModelPickerMenu`) showing live context window usage, token breakdown (`systemTokens` / `toolsTokens` / `messageTokens`), **prompt cache hit rate** (`cacheReadTokens / (uncachedInputTokens + cacheReadTokens + cacheWriteTokens)`, plus last-turn hit rate), expandable **System Prompt** sections & dynamic runtime contexts (with one-tap copy), registered **Tools** (built-in & MCP badges), and discovered **Skills**.
- **Cleaner Model Picker (`ModelPickerMenu`)**:
  - Removed verbose multi-line English model descriptions under model titles in `ModelList` for a cleaner single-line model selection menu.

### Added — Rust Native Core (`rust/dshn-core` & `rust/dshn-mcp-relay`)
- **Native Rust JNI Engine (`libdshn_core.so`, 16 KB page-aligned AArch64)**:
  - Single-pass streaming `.tar.gz` (`flate2`/`zlib-rs` SIMD) and `.tar.zst` (`ruzstd`) archive extractor with hardware SHA-256 verification, parent-directory deduplication cache, and direct POSIX `chmod`/`symlink`, reading uncompressed APK `.bin` slices zero-copy directly from `base.apk`.
  - Native Rust TLS + zero-copy SSE **Instant Fast-Chat** client (`DshnNativeCore.runFastChatStream` via `ureq` + `rustls` + `ring` ARMv8 crypto), enabling ~0.4 s cold-start chat before Ubuntu/Node/DSH finishes booting.
  - Zero-copy RGBA `DirectByteBuffer` -> FNV-1a dirty-frame skip -> SIMD JPEG encoder (`DshnNativeCore.encodeVirtualFrame`) for Android 14+ Virtual Display preview & screenshots.
- **Static Rust MCP UDS Relay (`libdshn_mcp_relay.so`, `aarch64-unknown-linux-musl`, 390 KB)**:
  - Replaces spawning a full Node 24 V8 process (`/opt/node24/bin/node dshn-mcp-relay.mjs`) per ACP session inside `bxroot`.
- **Release & StagingRelease R8 Build Types**:
  - Enabled `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-rules.pro`, `androidx.profileinstaller:1.4.1`, parallel/cached Gradle (`-Xmx4g`), and `-PdshnSkipBundledEnv=true` fast-iteration switch.
- **Android 14+ Background Virtual Display (`VirtualDisplayAgent` + `VirtualDisplayController`)**:
  - Added privileged `app_process` virtual display helper (MIT-adapted from DSH Sugar) using `DisplayManager.createVirtualDisplay` (`SUPPORTS_TOUCH | TRUSTED | OWN_DISPLAY_GROUP | OWN_FOCUS`), `ImageReader`, `UiAutomation` (`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`), and direct Android 14+ `InputManager.injectInputEvent` — bypassing OEM blocks on `overlay_display_devices`.
  - Connects via either user-authorized Root (`su`) or Wireless ADB (`shell,v2,raw:` stream), and delegates RGBA `DirectByteBuffer` -> JPEG + dirty-frame hashing straight to Rust `libdshn_core.so`.
  - Added `device_virtual_display_open` / `device_virtual_display_action` MCP tools (plus automatic `display_id` routing in `device_screenshot`/`device_screen_tree`/`device_tap`/`device_swipe`/`device_input`) and an interactive live virtual-screen preview card in `DeviceBridgeScreen`.

### Changed — Cold-Start, Session & Streaming Performance
- Adapted MIT-licensed **DSH Sugar** `profile-resolution` fast-path patch into `EnvManager.applyDshProfileResolutionOptimization()` (eliminates ~46% redundant `lstat`/`realpathSync` during DSH boot; unit tested).
- Enabled Node 24 persistent V8 compile cache (`NODE_COMPILE_CACHE=/root/.cache/dshn-v8`) in `ContainerLauncher` with automatic zero-byte `pwritev` self-heal guard.
- Made Device Bridge MCP attachment on-demand (`RuntimeSupervisor`: skip mounting relay when `bridgeEnabled == false`).
- Moved `AppContainer` / `EnvironmentCoordinator` / `ContainerLauncher` / `EnvManager` disk & socket initialization off the UI main thread; eliminated redundant `fsync` when runtime assets are unchanged.
- Replaced `250 ms` polling in `waitForHealthyRuntime()` with zero-latency `StateFlow.first`, stepped `waitForBridgePing()` backoff (`30 -> 200 ms`), parallelized `refreshSessions()`, and added `40 ms` coalesced streaming token updates (0 ms TTFT penalty).
- Replaced reflection-based `android.system.Os` calls in `TarGzExtractor` with direct Android SDK calls + parent-directory deduplication cache.

### Fixed — Live Device Verification Fixes (vivo V2352A / Android 16)
- **Static Rust MCP Relay UDS Path**: Static `musl` binary `dshn-mcp-relay` now connects directly to `DSHN_DEVICE_MCP_HOST_SOCK` (`/data/data/dev.dshn.app/files/bridge/device-mcp.sock`) first, bypassing `bxroot`'s `LD_PRELOAD` path translation (fixes `os error 2` on `/run/dshn-bridge/device-mcp.sock`).
- **Virtual Display Preview Polling Mutex Starvation**: Replaced fire-and-forget 450 ms frame polling in `DeviceBridgeScreen` with suspending non-blocking `VirtualDisplayController.observeIfIdle` (`tryLock` + `wait_frame_ms = 0`), preventing background preview polling from starving `launchApp` / `tap` / `sendKey` / `close` operations on `opMutex`. Added automatic `127.0.0.1:5555` loopback fallback and actionable Chinese error prompts when Wireless ADB has not been connected yet.
- **DSH `0.2.2-rc.5` Credentials Schema Migration**: Added idempotent `EnvManager.migrateLegacyCredentialsYaml` supporting pre-release flat YAML, YAML document markers (`---`), nested `version:` fields, flow mappings, and missing `version: 1` headers with `0600` permissions.

## Unreleased (aistudio-refactor, 2026-11-24)

### Changed — Architecture
- Modularized DSHN into 11 Gradle modules (`:app`, 5 `:core:*` modules, 5 `:feature:*` modules) with explicit constructor-injection `AppContainer`, type-safe `navigation-compose` routes, and lazy `:app` process initialization (keeping `:runtime` isolated).

### Changed — Chat composer
- Model and reasoning effort are now one chip ("<model> · <effort>") that opens a compact dropdown instead of two bottom sheets: models and the current model's efforts side by side on screens ≥ 600×480 dp, or two levels (model → effort, with a back row) on phones. Picking another model switches immediately and then shows that model's efforts, since DSH only advertises efforts for the current model and resets the effort on a model switch. Known effort ids (off / low / high / max / default) get Chinese names and descriptions. Other config options use the same single-level dropdown. `RuntimeSessionCoordinator.setConfigOption` gained an `onResult` callback (new option list, or `null` when nothing was applied).

### Fixed — Post-modularization review fixes
- Re-evaluate `AppContainer.resolveInitialSetupRequired()` on every `MainActivity.onCreate` so finishing the setup wizard and reopening the app within the same cached process lands on Chat instead of looping back to Setup.
- Stop container PTY and host `uid=0` Root shell in `TerminalViewModel.onCleared()` when `MainActivity` finishes.
- Evaluate live `onboardingCompleted` at bundled-environment install completion in `EnvironmentCoordinator` so finishing setup while extraction is still running automatically starts the runtime once extraction succeeds.
- Use idempotent `popBackStack<SetupRoute>(inclusive = true)` when dismissing a re-opened Setup wizard to prevent double-tap back-stack over-popping.
- Guard `upgradeDshToVersion`, `createEnvSnapshot`, `restoreEnvSnapshot`, and `runSelfTest` with `isInstallBusy()` (plus duplicate-run guard in `runSelfTest`).
- Restored security-invariant KDoc comments in `RootCommandPolicy`, `TarGzExtractor`, and `SettingsStore`.

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
- Host Root terminal (`HostRootShellSession` + `TerminalScreen`): normalize bare LF (`\n`) pipe output to CRLF (`\r\n`) to eliminate staircase indentation, echo submitted commands (`# <cmd>`), and default Host Root mode to line-input mode (keeping live key mode default for Ubuntu PTY).

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
