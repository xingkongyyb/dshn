/**
 * Insert-only Cordis plugin injected via `dsh --profile acp --patch /opt/dshn/dshn-bridge.patch.yml`.
 * Exposes read-only session listing / surface projection, live context inspection
 * (system prompt, tools, skills, token breakdown & cache hit rate), slash commands,
 * DSH plugin bundle management, and human-in-the-loop ask_user_question responses
 * over a bind-mounted Unix domain socket.
 *
 * Security invariant: Never moves, modifies, or quarantines unreadable/corrupt session files.
 */
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { execFile } from "node:child_process";

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

function truncateText(text, maxChars) {
  const s = typeof text === "string" ? text : "";
  if (s.length <= maxChars) return { text: s, truncated: false };
  return { text: s.slice(0, maxChars), truncated: true };
}

function interpolateTemplate(text, variables) {
  if (typeof text !== "string" || !variables || typeof variables !== "object") {
    return typeof text === "string" ? text : "";
  }
  return text.replace(/\{\{([a-z][a-z0-9_]*)\}\}/g, (full, key) => {
    const val = variables[key];
    return typeof val === "string" ? val : full;
  });
}

function inferToolSource(toolName) {
  const n = String(toolName || "");
  if (n.includes("__") || n.startsWith("mcp_") || n.startsWith("mcp:") || n.startsWith("dshn-device-bridge")) {
    return "mcp";
  }
  return "builtin";
}

function checkPnpmAvailable() {
  const candidates = [
    "/opt/node24/bin/pnpm",
    "/usr/local/bin/pnpm",
    "/usr/bin/pnpm",
  ];
  for (const p of candidates) {
    try {
      fs.accessSync(p, fs.constants.X_OK);
      return true;
    } catch {}
  }
  const pathEnv = process.env.PATH || "";
  for (const dir of pathEnv.split(":")) {
    if (!dir) continue;
    try {
      fs.accessSync(path.join(dir, "pnpm"), fs.constants.X_OK);
      return true;
    } catch {}
  }
  return false;
}

function pickLocalizedMeta(bundle, locale) {
  const wantZh = typeof locale === "string" && locale.toLowerCase().startsWith("zh");
  const meta = bundle?.meta ?? {};
  const locales = meta?.locales ?? {};
  const preferredOrder = wantZh
    ? ["zh-CN", "zh-Hans", "zh", "en-US", "en"]
    : ["en-US", "en", "zh-CN", "zh"];

  let localizedTitle = null;
  let localizedDesc = null;
  for (const lang of preferredOrder) {
    const entry = locales[lang];
    if (entry && typeof entry === "object") {
      if (!localizedTitle && typeof entry.displayName === "string" && entry.displayName.trim()) {
        localizedTitle = entry.displayName.trim();
      }
      if (!localizedTitle && typeof entry.title === "string" && entry.title.trim()) {
        localizedTitle = entry.title.trim();
      }
      if (!localizedTitle && typeof entry.name === "string" && entry.name.trim()) {
        localizedTitle = entry.name.trim();
      }
      if (!localizedDesc && typeof entry.description === "string" && entry.description.trim()) {
        localizedDesc = entry.description.trim();
      }
    }
  }
  const fallbackShortName = String(bundle?.name ?? "")
    .replace(/^@deepseek-ai\/dsh-/, "")
    .replace(/^@deepseek-ai\//, "");

  const displayName =
    localizedTitle ||
    (typeof meta.displayName === "string" && meta.displayName.trim()) ||
    (typeof meta.title === "string" && meta.title.trim()) ||
    fallbackShortName ||
    String(bundle?.name ?? "");

  const description =
    localizedDesc ||
    (typeof meta.description === "string" && meta.description.trim()) ||
    (typeof bundle?.description === "string" && bundle.description.trim()) ||
    "";

  return { displayName, description };
}

function extractLastTurnUsage(events) {
  if (!Array.isArray(events)) return null;
  for (let i = events.length - 1; i >= 0; i--) {
    const ev = events[i];
    if (!ev || (ev.type !== "assistant/message" && ev.type !== "assistant/attempt")) continue;
    let usage = ev.data?.usage;
    if (!usage && Array.isArray(ev.data?.stream)) {
      for (let j = ev.data.stream.length - 1; j >= 0; j--) {
        const chunk = ev.data.stream[j];
        if (chunk?.type === "usage" && chunk.usage) {
          usage = chunk.usage;
          break;
        }
      }
    }
    if (usage && typeof usage.inputTokens === "number") {
      const uncached = Math.max(0, Number(usage.inputTokens) || 0);
      const cacheRead = Math.max(0, Number(usage.cacheReadTokens) || 0);
      const cacheWrite = Math.max(0, Number(usage.cacheWriteTokens) || 0);
      const output = Math.max(0, Number(usage.outputTokens) || 0);
      const totalIn = uncached + cacheRead + cacheWrite;
      return {
        uncachedInputTokens: uncached,
        outputTokens: output,
        cacheReadTokens: cacheRead,
        cacheWriteTokens: cacheWrite,
        cacheHitRate: totalIn > 0 ? cacheRead / totalIn : null,
      };
    }
  }
  return null;
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
    const list = agents.list?.() ?? [];
    // DSH 0.2.2-rc.5+ distinguishes ordinary forked sessions from explicit subagents via header.origin.
    const ordinary = list.find((a) => a?.session?.header?.origin !== "subagent");
    if (ordinary) return ordinary;
    const roots = agents.roots?.() ?? [];
    return roots[0] ?? list[0];
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

  async function inspectContext(sessionId) {
    const agent = findAgent(sessionId);
    const resolvedSessionId = agent?.session?.id ?? sessionId ?? null;
    const cwd = agent?.cwd ?? agent?.session?.header?.cwd ?? null;

    let tokenUsage = {
      uncachedInputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      cacheHitRate: null,
    };
    let lastTurnUsage = null;
    let usedTokens = 0;
    let contextWindow = 0;
    let breakdown = {
      systemTokens: 0,
      toolsTokens: 0,
      messageTokens: 0,
    };

    const projections = ctx.get("sessionProjections");
    if (projections && agent?.session) {
      try {
        const snap = await Promise.resolve(
          projections.snapshot(agent.session, ["tokenUsage", "contextPressure", "contextBreakdown"])
        );
        const vals = snap?.values ?? snap ?? {};
        const tu = vals.tokenUsage;
        if (tu && typeof tu === "object") {
          const uncached = Math.max(0, Number(tu.uncachedInputTokens) || 0);
          const output = Math.max(0, Number(tu.outputTokens) || 0);
          const cacheRead = Math.max(0, Number(tu.cacheReadTokens) || 0);
          const cacheWrite = Math.max(0, Number(tu.cacheWriteTokens) || 0);
          const totalIn = uncached + cacheRead + cacheWrite;
          tokenUsage = {
            uncachedInputTokens: uncached,
            outputTokens: output,
            cacheReadTokens: cacheRead,
            cacheWriteTokens: cacheWrite,
            cacheHitRate: totalIn > 0 ? cacheRead / totalIn : null,
          };
        }
        const cp = vals.contextPressure;
        if (cp && typeof cp === "object") {
          usedTokens = Math.max(0, Number(cp.projectedTokens ?? cp.pressureTokens ?? 0) || 0);
          contextWindow = Math.max(0, Number(cp.contextWindow ?? 0) || 0);
        }
        const cb = vals.contextBreakdown;
        if (cb && typeof cb === "object") {
          breakdown = {
            systemTokens: Math.max(0, Number(cb.systemTokens) || 0),
            toolsTokens: Math.max(0, Number(cb.toolsTokens) || 0),
            messageTokens: Math.max(0, Number(cb.messageTokens) || 0),
          };
        }
      } catch (err) {
        logger?.warn?.(`[dshn-session-bridge] sessionProjections snapshot failed: ${err?.message ?? err}`);
      }
    }

    // Best-effort last-turn cache usage from session surface if available
    if (resolvedSessionId) {
      const sq = ctx.get("sessionQuery");
      if (sq && typeof sq.readSurface === "function") {
        try {
          const surface = await sq.readSurface(resolvedSessionId);
          lastTurnUsage = extractLastTurnUsage(surface?.events);
        } catch {}
      }
    }

    // Live system prompt sections, dynamic contexts, and registered tool schemas
    let totalPromptChars = 0;
    const sections = [];
    const contexts = [];
    const tools = [];
    const sysPromptSvc = ctx.get("systemPrompt");
    if (sysPromptSvc && typeof sysPromptSvc.assemble === "function") {
      try {
        const assembled = await sysPromptSvc.assemble({
          agent,
          scope: agent,
          cwd: cwd ?? "/root",
        });
        const vars = assembled?.variables ?? {};
        for (const sec of assembled?.sections ?? []) {
          const rawText =
            sec?.interpolate === false
              ? String(sec?.text ?? "")
              : interpolateTemplate(String(sec?.text ?? ""), vars);
          if (!rawText) continue;
          totalPromptChars += rawText.length;
          const preview = truncateText(rawText, 3000);
          sections.push({
            name: String(sec?.name ?? "section"),
            chars: rawText.length,
            preview: preview.text,
            truncated: preview.truncated,
          });
        }
        for (const c of assembled?.contexts ?? []) {
          const rawText = String(c?.text ?? "");
          if (!rawText) continue;
          const preview = truncateText(rawText, 1600);
          contexts.push({
            name: String(c?.name ?? "context"),
            chars: rawText.length,
            preview: preview.text,
            truncated: preview.truncated,
          });
        }
        for (const tool of assembled?.tools ?? []) {
          const desc = truncateText(String(tool?.description ?? "").replace(/\s+/g, " ").trim(), 220);
          tools.push({
            name: String(tool?.name ?? ""),
            description: desc.text,
            source: inferToolSource(tool?.name),
            deferLoading: Boolean(tool?.deferLoading),
          });
        }
      } catch (err) {
        logger?.warn?.(`[dshn-session-bridge] systemPrompt.assemble failed: ${err?.message ?? err}`);
      }
    }

    // Live discovered skills
    const skills = [];
    const skillsSvc = ctx.get("skills");
    if (skillsSvc && typeof skillsSvc.snapshot === "function") {
      try {
        const snap = await skillsSvc.snapshot({
          cwd: cwd ?? "/root",
          scope: agent,
        });
        for (const s of snap?.skills ?? []) {
          const base = s?.resourceBase;
          let locPath = null;
          if (base?.kind === "directory" && typeof base.path === "string") {
            locPath = base.path;
          } else if (base?.kind === "url" && typeof base.url === "string") {
            locPath = base.url;
          }
          skills.push({
            name: String(s?.name ?? ""),
            description: truncateText(String(s?.description ?? "").trim(), 240).text,
            whenToUse: typeof s?.whenToUse === "string" ? s.whenToUse.trim() : null,
            provider: String(s?.provider ?? "unknown"),
            path: locPath,
            modelInvocable: s?.invocation?.modelInvocable !== false,
            userInvocable: s?.invocation?.userInvocable !== false,
          });
        }
      } catch (err) {
        logger?.warn?.(`[dshn-session-bridge] skills.snapshot failed: ${err?.message ?? err}`);
      }
    }

    return {
      ok: true,
      sessionId: resolvedSessionId,
      cwd,
      hasLiveAgent: Boolean(agent),
      usedTokens,
      contextWindow,
      breakdown,
      tokenUsage,
      lastTurnUsage,
      systemPrompt: {
        totalChars: totalPromptChars,
        estimatedTokens: breakdown.systemTokens > 0 ? breakdown.systemTokens : Math.ceil(totalPromptChars / 3.5),
        sections,
        contexts,
      },
      tools,
      skills,
    };
  }

  async function listPlugins(locale) {
    const pm = ctx.get("pluginManager");
    const pnpmAvailable = checkPnpmAvailable();
    const hmrEnabled = Boolean(ctx.get("hmr"));
    if (!pm || typeof pm.listBundles !== "function") {
      return {
        ok: false,
        available: false,
        hmrEnabled,
        pnpmAvailable,
        profile: "acp",
        bundles: [],
        error: "pluginManager service is not loaded",
      };
    }
    const rawBundles = await Promise.resolve(pm.listBundles());
    const bundles = (Array.isArray(rawBundles) ? rawBundles : []).map((b) => {
      const { displayName, description } = pickLocalizedMeta(b, locale);
      const rows = Array.isArray(b.rows)
        ? b.rows.map((r) => ({
            id: String(r?.id ?? r?.name ?? ""),
            name: String(r?.name ?? r?.id ?? ""),
            enabled: r?.enabled !== false,
          }))
        : [];
      return {
        name: String(b.name ?? ""),
        displayName,
        description,
        version: typeof b.version === "string" ? b.version : null,
        availability: String(b.availability ?? "missing"),
        official: Boolean(b.official),
        optional: Boolean(b.optional),
        enabled: Boolean(b.enabled),
        installed: Boolean(b.installed),
        removable: Boolean(b.removable),
        readOnlyReason: typeof b.readOnlyReason === "string" ? b.readOnlyReason : null,
        listedByDefault: b.listedByDefault !== false,
        pluginCount: rows.length,
        plugins: rows,
        errorCode: typeof b.error?.code === "string" ? b.error.code : null,
        errorMessage:
          typeof b.error?.message === "string"
            ? b.error.message
            : typeof b.error?.code === "string"
              ? b.error.code
              : null,
      };
    });
    return {
      ok: true,
      available: true,
      hmrEnabled,
      pnpmAvailable,
      profile: "acp",
      bundles,
    };
  }

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
            hasAttachments: Boolean(ctx.get("attachments")),
            hasAgentTeam: Boolean(ctx.get("agentTeam")),
            hasPluginManager: Boolean(ctx.get("pluginManager")),
            hasSystemPrompt: Boolean(ctx.get("systemPrompt")),
            hasSkills: Boolean(ctx.get("skills")),
            hasSessionProjections: Boolean(ctx.get("sessionProjections")),
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
              origin: entry.header.origin ?? null,
              agentPreset: entry.header.agentPreset ?? null,
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

        case "context.inspect": {
          const data = await inspectContext(params.sessionId ?? null);
          return reply(data);
        }

        case "plugins.list": {
          const data = await listPlugins(params.locale ?? "zh-CN");
          return reply(data);
        }

        case "plugins.setEnabled": {
          const pm = ctx.get("pluginManager");
          if (!pm || typeof pm.setBundleEnabled !== "function") {
            return fail("UNAVAILABLE", "pluginManager service is not loaded");
          }
          const bundleName = typeof params.name === "string" ? params.name.trim() : "";
          if (!bundleName) return fail("INVALID_PARAMS", "name is required");
          const enabled = Boolean(params.enabled);
          const res = await pm.setBundleEnabled(bundleName, enabled);
          const appState = String(res?.application ?? "unknown");
          const ok = appState !== "failed" && appState !== "cancelled";
          return reply({
            ok,
            name: bundleName,
            application: appState,
            restartRequired: appState === "restart-required" || (ok && !ctx.get("hmr")),
            diagnostic:
              res?.diagnostic ??
              res?.error?.message ??
              res?.error?.code ??
              null,
          });
        }

        case "plugins.install": {
          const pm = ctx.get("pluginManager");
          if (!pm || typeof pm.installBundle !== "function") {
            return fail("UNAVAILABLE", "pluginManager service is not loaded");
          }
          const spec = typeof params.spec === "string" ? params.spec.trim() : "";
          if (!spec) return fail("INVALID_PARAMS", "spec is required");
          const localCandidate = spec.replace(/^(?:file|link):/, "");
          if ((localCandidate !== spec || path.isAbsolute(localCandidate)) && !fs.existsSync(localCandidate)) {
            return reply({
              ok: false,
              application: "failed",
              restartRequired: false,
              diagnostic: `Local plugin path not found in container: ${localCandidate}`,
            });
          }
          if (!checkPnpmAvailable()) {
            return reply({
              ok: false,
              missingPnpm: true,
              application: "failed",
              restartRequired: false,
              diagnostic: "pnpm is not installed in the container (/opt/node24/bin/pnpm missing)",
            });
          }
          const res = await pm.installBundle(spec, { enabled: params.enabled !== false });
          const appState = String(res?.application ?? "unknown");
          const ok = appState !== "failed" && appState !== "cancelled";
          const pkgOut = typeof res?.packageResult?.output === "string" ? res.packageResult.output.trim() : "";
          const errCode = res?.error?.code;
          const friendlyCode =
            errCode === "not-bundle"
              ? "Package does not declare a DSH bundle manifest (missing package.json dsh.bundle)"
              : errCode === "incompatible-version"
                ? "Plugin package version is incompatible with current DSH runtime"
                : null;
          const diag =
            friendlyCode ??
            res?.diagnostic ??
            res?.error?.message ??
            errCode ??
            (pkgOut ? pkgOut.slice(-450) : null);
          return reply({
            ok,
            name: typeof res?.target === "string" ? res.target : spec,
            application: appState,
            restartRequired: appState === "restart-required" || (ok && !ctx.get("hmr")),
            diagnostic: diag,
          });
        }

        case "plugins.remove": {
          const pm = ctx.get("pluginManager");
          if (!pm || typeof pm.removeBundle !== "function") {
            return fail("UNAVAILABLE", "pluginManager service is not loaded");
          }
          const bundleName = typeof params.name === "string" ? params.name.trim() : "";
          if (!bundleName) return fail("INVALID_PARAMS", "name is required");
          const res = await pm.removeBundle(bundleName);
          const appState = String(res?.application ?? "unknown");
          const ok = appState !== "failed" && appState !== "cancelled";
          return reply({
            ok,
            name: bundleName,
            application: appState,
            restartRequired: appState === "restart-required" || (ok && !ctx.get("hmr")),
            diagnostic:
              res?.diagnostic ??
              res?.error?.message ??
              res?.error?.code ??
              null,
          });
        }

        case "plugins.installPnpm": {
          if (checkPnpmAvailable()) {
            return reply({ ok: true, application: "ready", restartRequired: false, diagnostic: "pnpm already installed" });
          }
          const npmBin = fs.existsSync("/opt/node24/bin/npm") ? "/opt/node24/bin/npm" : "npm";
          const env = {
            ...process.env,
            PATH: `/opt/node24/bin:${process.env.PATH || "/usr/local/bin:/usr/bin:/bin"}`,
          };
          const output = await new Promise((resolve) => {
            execFile(
              npmBin,
              ["install", "-g", "pnpm", "--no-audit", "--no-fund"],
              { env, timeout: 150000, maxBuffer: 1024 * 1024 },
              (error, stdout, stderr) => {
                resolve({
                  ok: !error && checkPnpmAvailable(),
                  diagnostic: [stdout, stderr, error?.message].filter(Boolean).join("\n").trim().slice(-500),
                });
              }
            );
          });
          return reply({
            ok: output.ok,
            application: output.ok ? "ready" : "failed",
            restartRequired: false,
            diagnostic: output.diagnostic || null,
          });
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
