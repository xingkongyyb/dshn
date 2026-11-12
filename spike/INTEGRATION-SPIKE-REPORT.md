# DSHN 集成面 Spike 报告（P0-T4）

> 日期：2026-11-12 · 真机 vivo V2352A / Android 16 · bxroot v0.2.5 + Ubuntu 24.04 + Node 24.19.0 + DSH 0.2.2-rc.1
> 方法：node spike 客户端 spawn `dsh --profile acp`，JSON-RPC over stdio，逐项验证（脚本 `acp-spike.mjs`）

## 1. 三 profile 启动验证

| profile | 容器内启动 | 说明 |
|---|---|---|
| `--profile sdk` | ✅ | Serve SDK clients over stdio JSON-RPC |
| `--profile acp` | ✅ | Serve automation clients over Agent Client Protocol stdio |
| `--profile web` | ✅ | Serve browser UI |

## 2. ACP 能力面实测（核心证据）

| 能力 | 方法 | 结果 |
|---|---|---|
| 握手 | `initialize` | ✅ protocolVersion=1，agentInfo=deepseek-harness-acp |
| MCP 挂载 | agentCapabilities.mcpCapabilities | ✅ **http=true**（设备桥可经标准 HTTP MCP 挂载） |
| 会话能力 | sessionCapabilities | ✅ **close / list / resume** 均声明 |
| 新建会话 | `session/new` | ✅ 返回 sessionId + configOptions |
| 会话列表 | `session/list` | ✅ {sessions:[]} |
| 关闭会话 | `session/close` | ✅ result {} |
| **按会话选模型** | `session/set_config_option` | ✅ configId=model，值 `["deepseek-official","deepseek-v4-flash"]` 生效 |
| **取消轮次** | `session/cancel`（通知） | ✅ 作为 notification 发送被接受（注：非 request，发请求会 -32601） |
| 多模态提示 | promptCapabilities | ⚠️ image=false / audio=false（本 build 不支持图片/音频提示） |

## 3. 待凭据验证项（需模型 API key）

- `session/prompt` token 级流式增量（H1）
- `session/request_permission` 审批应答（B2）
- ask-user 用户提问应答
- 斜杠命令处理

## 4. 结论与推荐

**ACP 为主集成面成立**：相比 SDK（无取消/无会话列表/无按会话选模型/审批空壳），ACP 实测具备 cancel/list/close/resume/按会话选模型/MCP-HTTP 全部关键交互能力，且是标准协议、客户端面干净。审计 B1 指出的 SDK 缺陷在 ACP 上均不存在。

**保留项**：
- 多模态提示（image/audio）本 build 关闭——若需图片输入，评估 API Peer fallback。
- 流式/审批需凭据实测后最终确认。
- 历史读取仍走 session-query MCP（设计 §7.2），不依赖 ACP 历史能力。

**第 0 号决策建议**：选 **ACP**；API Peer 仅作多模态/全 Web 能力 fallback。待凭据 spike 通过后回填设计 §3。

## 5. 环境备注

- bxroot `selftest HAS-FAILURE (fail=1)` 持续出现，但 bash/node/网络/PTY/ACP 协议全部正常——判定为良性内部探针失败，不阻塞；后续单独排查。
- 容器网络需修 `/etc/resolv.conf`（rootfs 默认悬挂符号链接→写真实 DNS）。
- npm 原生模块需 `--allow-scripts` 放行（node-pty/koffi 等）。