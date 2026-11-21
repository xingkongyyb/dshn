# 真机冒烟测试：Android 11 平板（2026-11-21）

> 轻量测试（用户额度有限，按“大概看看”执行）。平板未配置 API Key，因此**没有测试实际对话**。

## 1. 测试环境

| 项 | 值 |
|---|---|
| 设备 | Phh-Treble vanilla GSI（`treble_arm64_bvS`），ADB 序列号 `K3220230500816` |
| 系统 | **Android 11（API 30，= 本应用 minSdk）**，内核 `4.19.127`，arm64-v8a |
| 屏幕 | 1200×2000，240 dpi（平板竖屏） |
| Root | phh-superuser（`/system/xbin/su`）；本次**未**给 DSHN 授予 Root |
| 被测版本 | DSHN `0.3.2`（versionCode 5），2026-11-21 18:08 构建，对应本地分支 `ui-redesign` @ `6f4ad37`（Gemini 18:09 安装） |
| App 状态 | 内置环境已解压（`bundledEnvVersion=0.3.1-u24.04.5-n24.22.3-dsh0.2.2-rc.5-acp-glibc`，`rootfsReady=true`）；首启向导已完成；**无 API Key**；执行权限 `DANGER_FULL_ACCESS` |

## 2. 结果总览

| # | 用例 | 结果 | 说明 |
|---|---|---|---|
| 1 | 冷启动 → 对话主页 | ✅ 通过 | 顶栏“未设置 API Key”、缺 Key 横幅与“去设置”、空状态卡片、输入框均正常（`01_launch.small.jpg`） |
| 2 | 设置 › 运行环境 · 组件状态 | ✅ 通过 | Ubuntu 已就绪 / Node v24.22.3 / DSH 0.2.2-rc.5 / 桥接组件已同步 |
| 3 | 设置 › 运行环境 · 运行自检 | ❌ **3/5 失败** | `rootfs_layout` ✓、`node_runtime` ✓；`dsh_and_bridge_syntax`、`node_pty`、`dns_deepseek_api` 均 **exit=159**（`02_selftest.small.jpg`） |
| 4 | 终端 · 启动 Shell | ❌ **静默失败** | 点击后几秒内回到“未启动”，界面无任何错误提示（`03_terminal.small.jpg`） |
| 5 | 同样命令经 `run-as` 手动复现 | ✅ 全部通过 | 见第 3 节：容器、Node、桥接脚本、DNS 预加载本身都没问题 |
| — | 对话 / 启动运行环境 | 未测 | 没有 API Key，运行环境按设计拒绝启动 |
| — | Root 授权、设备桥 / ADB、无障碍 | 未测 | Root 授权需要在超级用户管理器里手动点允许，留给用户 |
| — | 首启向导 + 内置环境解压（全新安装） | 未测 | 需清空 App 数据并重新解压约 1 GB，这次不做 |

## 3. 关键问题：Android 11 上容器内 Node 脚本被系统杀掉（exit=159 = SIGSYS）

**现象**：自检里凡是“真正执行 JS”的检查都返回 `exit=159`，只有 `node -v` 通过；终端（同样由 Node 运行 `dshn-pty-runner.mjs`）一启动就退出。

**复现对照**（`probe.sh`，以 App 的 uid 通过 `run-as` 执行与 `ContainerLauncher` 完全相同的 bxroot 命令行和环境变量）：

```
exit=0 :: /bin/sh -c echo sh_ok
exit=0 :: /opt/node24/bin/node -v                                   -> v24.22.3
exit=0 :: /opt/node24/bin/node -e console.log("node_eval_ok")
exit=0 :: /opt/node24/bin/node --check /opt/dshn/dshn-session-bridge.mjs
exit=0 :: NODE_OPTIONS=--require=/opt/dshn/dshn-dns-preload.cjs node -e …
exit=0 :: UV_USE_IO_URING=0 node -e …
exit=0 :: node --v8-pool-size=0 -e …
```

`run-as` 启动的进程（SELinux 上下文 `runas_app`）**不带** zygote 给 App 进程装的 seccomp 过滤器；App 内启动的进程（logcat 显示上下文 `untrusted_app`）带。命令、文件、环境完全相同，结果却不同，所以问题出在 **App 进程的 seccomp 策略**。

**推断原因（高度可信，但尚未拿到被拦截的系统调用号）**：
- 159 = 128 + 31，31 = SIGSYS；Android 的 App seccomp 用 `SECCOMP_RET_TRAP` 拦截禁止的系统调用，不写审计日志，所以 dmesg 里查不到。
- Ubuntu 24.04 的 glibc（≥ 2.34）在 `pthread_create` 时优先使用 `clone3`。Android 11 的 App seccomp 策略不允许 `clone3`，直接发 SIGSYS；Android 12 起改为对 `clone3` 返回 `ENOSYS`，glibc 会自动退回 `clone`。
- `node -v` 在创建任何线程前就退出所以能过；执行脚本时 Node/V8 会起工作线程，于是被杀。这与“vivo（Android 16）正常、本机 Android 11 失败”一致。

**影响**：在 Android 11 上 DSH 运行环境（`dsh --profile acp` 也是 Node）预计无法启动，即 **minSdk 30 目前在实际上不可用**；Android 12+ 不受影响（待更多机型确认）。

## 4. 建议（未实施）

1. **确认系统调用号**：在 App 进程里跑一个最小线程程序，或给容器进程装 SIGSYS 处理器打印 `si_syscall`（arm64 上 `clone3` = 435）。
2. **兼容修复候选**：给容器内进程注入一个预加载垫片，在 SIGSYS 处理器里把被拦截的 `clone3` 改成返回 `-ENOSYS`（glibc 随后自动退回 `clone`）；或者先确认 bxroot 是否已有系统调用钩子可以做同样的事。
3. **若暂不修**：把 minSdk 提到 31（Android 12），并在 README / 设计稿里注明。
4. **体验**：终端启动失败、自检 `exit=159` 时，应提示“进程被系统 seccomp 拦截（SIGSYS），Android 11 暂不支持”，而不是静默退回“未启动”或只显示退出码。

## 5. 附件

- `01_launch.small.jpg`：冷启动对话主页（缺 Key 状态）
- `02_selftest.small.jpg`：运行环境页与自检结果
- `03_terminal.small.jpg`：终端启动后回到“未启动”
- `probe.sh`：复现脚本。用法：`adb push probe.sh /data/local/tmp/` 后执行
  `adb shell "run-as dev.dshn.app /system/bin/sh /data/local/tmp/probe.sh '<legacyNativeLibraryDir>/arm64'"`
  （`legacyNativeLibraryDir` 取自 `adb shell dumpsys package dev.dshn.app`）
- 测试结束后已删除平板上的临时文件（`/data/local/tmp/dshn_probe*`、`/sdcard/dshn_ui.xml`）；App 数据与设置未改动。
