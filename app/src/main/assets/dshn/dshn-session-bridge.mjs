/**
 * Insert-only Cordis plugin injected via `dsh --profile acp --patch /opt/dshn/dshn-bridge.patch.yml`.
 * Exposes read-only session listing / surface projection, slash commands, and
 * human-in-the-loop ask_user_question responses over a bind-mounted Unix domain socket.
 *
 * Security invariant: Never moves, modifies, or quarantines unreadable/corrupt session files.
 */
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

export const name = "dshn-session-bridge";
export const inject = ["sessionQuery"];

function safeUnlink(sockPath) {
  try {
    if (fs.existsSync(sockPath)) fs.unlinkSync(sockPath);
  } catch {}
}

function sendLine(socket, payload) {
  if (!socket.destroyed && socket.writable) {
    socket.write(`${JSON.stringify(payload)}\n`);
  }
}

export function apply(ctx) {
  const sockPath = process.env.DSHN_SESSION_BRIDGE_SOCK || "/run/dshn-bridge/session-bridge.sock";
  const logger = ctx.get("logger");

  try {
    fs.mkdirSync(path.dirname(sockPath), { recursive: true, mode: 0o700 });
  } catch {}
  safeUnlink(sockPath);

  const subscribers = new Set();
  const pendingQuestions = new Map();

  function findAgent(sessionId) {
    const agents = ctx.get("agents");
    if (!agents) return undefined;
    if (sessionId) return agents.get(sessionId);
    const roots = agents.roots?.() ?? [];
    return roots[0] ?? agents.list?.()?.[0];
  }

  function liveSessionIds() {
    const agents = ctx.get("agents");
    const ids = new Set();
    if (!agents?.list) return ids;
    for (const agent of agents.list()) {
      const sid = agent?.session?.id ?? agent?.id;
      if (typeof sid === "string") ids.add(sid);
    }
    return ids;
  }

  const removeQuestionHook = ctx.on("user-questions/request", async (request, next) => {
    if (subscribers.size === 0) {
      return next();
    }
    const requestId = randomUUID();
    const sessionId = request.agent?.session?.id ?? null;
    const callId = request.wait?.callId ?? null;
    const timed = Boolean(request.wait?.timed);

    return new Promise((resolve, reject) => {
      const cleanup = () => {
        pendingQuestions.delete(requestId);
        if (request.signal) {
          request.signal.removeEventListener("abort", onAbort);
        }
      };
      const onAbort = () => {
        cleanup();
        for (const sub of subscribers) {
          sendLine(sub, { event: "question.cancelled", params: { requestId, sessionId, callId } });
        }
        const err = new Error("ask_user_question aborted");
        err.name = "AbortError";
        reject(err);
      };
      if (request.signal?.aborted) {
        onAbort();
        return;
      }
      pendingQuestions.set(requestId, { resolve, reject, cleanup });
      if (request.signal) {
        request.signal.addEventListener("abort", onAbort, { once: true });
      }
      const eventPayload = {
        event: "question.request",
        params: {
          requestId,
          sessionId,
          callId,
          timed,
          questions: request.questions ?? [],
        },
      };
      for (const sub of subscribers) {
        sendLine(sub, eventPayload);
      }
    });
  });

  async function handleRpc(socket, msg) {
    const { id, method, params = {} } = msg;
    const reply = (result) => sendLine(socket, { id, result });
    const fail = (code, message) => sendLine(socket, { id, error: { code, message } });

    try {
      switch (method) {
        case "ping": {
          return reply({
            ok: true,
            pid: process.pid,
            hasSessionQuery: Boolean(ctx.get("sessionQuery")),
            hasCommands: Boolean(ctx.get("commands")),
            hasAgents: Boolean(ctx.get("agents")),
            liveCount: liveSessionIds().size,
          });
        }

        case "events.subscribe": {
          subscribers.add(socket);
          return reply({ subscribed: true });
        }

        case "questions.answer": {
          const pending = pendingQuestions.get(params.requestId);
          if (!pending) return fail("NOT_FOUND", `Unknown question request: ${params.requestId}`);
          pending.cleanup();
          if (params.cancelled) {
            const err = new Error(params.reason || "Cancelled by user");
            err.name = "AbortError";
            pending.reject(err);
          } else {
            pending.resolve({ answers: Array.isArray(params.answers) ? params.answers : [] });
          }
          return reply({ ok: true });
        }

        case "sessions.list": {
          const sq = ctx.get("sessionQuery");
          if (!sq) return fail("UNAVAILABLE", "sessionQuery service is not loaded");
          const entries = await sq.listSessions();
          const liveIds = liveSessionIds();
          const isReadableStatus = (st) => !st || st === "current" || st === "ok" || st === "supported";
          const readableIds = [];
          for (const entry of entries) {
            if (isReadableStatus(entry.formatStatus)) {
              readableIds.push(entry.header.id);
            }
          }
          const titleMap = new Map();
          if (readableIds.length > 0 && typeof sq.readTitleSnapshots === "function") {
            const settled = await sq.readTitleSnapshots(readableIds);
            for (let i = 0; i < settled.length; i++) {
              const item = settled[i];
              const sid = item.sessionId ?? item.value?.session?.id ?? readableIds[i];
              if (item.status === "fulfilled" && item.value) {
                const rawTitle = item.value.title;
                const titleText = typeof rawTitle === "string" ? rawTitle : (rawTitle?.text ?? null);
                titleMap.set(sid, {
                  title: titleText,
                  corrupt: false,
                  error: null,
                });
              } else {
                titleMap.set(sid, {
                  title: null,
                  corrupt: true,
                  error: item.reason?.message ?? String(item.reason ?? "unreadable"),
                });
              }
            }
          }
          const sessions = entries.map((entry) => {
            const sid = entry.header.id;
            const titleInfo = titleMap.get(sid);
            const fmtBad = !isReadableStatus(entry.formatStatus);
            const corrupt = fmtBad || Boolean(titleInfo?.corrupt);
            return {
              id: sid,
              createdAt: entry.header.createdAt,
              cwd: entry.header.cwd ?? null,
              parentSession: entry.header.parentSession ?? null,
              isSeeded: Boolean(entry.header.isSeeded),
              delegationDepth: entry.header.delegationDepth ?? 0,
              live: Boolean(entry.live || liveIds.has(sid)),
              persisted: Boolean(entry.persisted),
              formatStatus: entry.formatStatus ?? "current",
              corrupt,
              readOnly: corrupt,
              title: titleInfo?.title ?? null,
              error: titleInfo?.error ?? (fmtBad ? `formatStatus=${entry.formatStatus}` : null),
            };
          });
          sessions.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
          return reply({ sessions });
        }

        case "sessions.surface": {
          const sq = ctx.get("sessionQuery");
          if (!sq) return fail("UNAVAILABLE", "sessionQuery service is not loaded");
          const sessionId = params.sessionId;
          if (!sessionId || typeof sessionId !== "string") {
            return fail("INVALID_PARAMS", "sessionId is required");
          }
          try {
            const surface = await sq.readSurface(sessionId);
            return reply({
              ok: true,
              sessionId,
              live: liveSessionIds().has(sessionId),
              readOnly: false,
              corrupt: false,
              inheritedEventCount: surface.inheritedEventCount ?? 0,
              capturedThroughSeq: surface.capturedThroughSeq ?? 0,
              session: surface.session ?? null,
              events: Array.isArray(surface.events) ? surface.events : [],
            });
          } catch (err) {
            // Do NOT touch, move, or rewrite the unreadable file. Return read-only error metadata.
            return reply({
              ok: false,
              sessionId,
              live: liveSessionIds().has(sessionId),
              readOnly: true,
              corrupt: true,
              code: err?.code ?? "SESSION_READ_ERROR",
              message: err?.message ?? String(err),
            });
          }
        }

        case "commands.list": {
          const cmds = ctx.get("commands");
          if (!cmds) return reply({ commands: [], unavailable: true });
          const agent = findAgent(params.sessionId);
          if (!agent) return reply({ commands: [], needsSession: true });
          const list = cmds.list(agent) ?? [];
          return reply({
            commands: list.map((c) => ({
              name: c.name,
              description: c.description ?? "",
              arguments: c.arguments ?? [],
            })),
          });
        }

        case "commands.execute": {
          const cmds = ctx.get("commands");
          if (!cmds) return fail("UNAVAILABLE", "commands service is not loaded");
          const line = params.line;
          if (!line || typeof line !== "string") return fail("INVALID_PARAMS", "line is required");
          const agent = findAgent(params.sessionId);
          if (!agent) return fail("SESSION_NOT_LIVE", "No live agent found for session");
          const ac = new AbortController();
          const timer = setTimeout(() => ac.abort(new Error("command execution timed out")), 120000);
          try {
            const executed = await cmds.execute(agent, line, [], ac.signal);
            if (!executed) return fail("UNKNOWN_COMMAND", `Unknown slash command: ${line}`);
            return reply({
              ok: executed.result?.kind !== "error",
              commandId: executed.commandId,
              result: executed.result ?? null,
            });
          } finally {
            clearTimeout(timer);
          }
        }

        default:
          return fail("METHOD_NOT_FOUND", `Unknown method: ${method}`);
      }
    } catch (err) {
      return fail(err?.code ?? "INTERNAL_ERROR", err?.message ?? String(err));
    }
  }

  const server = net.createServer((socket) => {
    let buf = "";
    socket.on("data", (chunk) => {
      buf += chunk.toString("utf8");
      for (;;) {
        const nl = buf.indexOf("\n");
        if (nl < 0) break;
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line) continue;
        try {
          const msg = JSON.parse(line);
          void handleRpc(socket, msg);
        } catch (err) {
          sendLine(socket, { id: null, error: { code: "PARSE_ERROR", message: err.message } });
        }
      }
    });
    socket.on("close", () => {
      subscribers.delete(socket);
    });
    socket.on("error", () => {
      subscribers.delete(socket);
    });
  });

  server.on("error", (err) => {
    logger?.warn?.(`[dshn-session-bridge] server error: ${err.message}`);
  });

  server.listen(sockPath, () => {
    try {
      fs.chmodSync(sockPath, 0o600);
    } catch {}
    logger?.info?.(`[dshn-session-bridge] listening on ${sockPath}`);
  });

  ctx.on("dispose", () => {
    removeQuestionHook?.();
    for (const [reqId, pending] of pendingQuestions.entries()) {
      pending.cleanup();
      pending.reject(new Error("Session bridge disposed"));
      pendingQuestions.delete(reqId);
    }
    try {
      server.close();
    } catch {}
    safeUnlink(sockPath);
  });
}
