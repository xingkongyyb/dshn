#!/opt/node24/bin/node
/**
 * Interactive PTY runner for the DSHN Terminal tab.
 * Uses node-pty shipped inside DSH and isolates DSH_HOME from ACP sessions.
 */
import fs from "node:fs";
import { createRequire } from "node:module";
import readline from "node:readline";

const NODE_PTY_CANDIDATES = [
  "/opt/node24/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/lib/index.js",
  "/opt/node24/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/node_modules/node-pty/lib/index.js",
  "/opt/node24/lib/node_modules/node-pty/lib/index.js",
];

async function loadNodePty() {
  for (const candidate of NODE_PTY_CANDIDATES) {
    if (fs.existsSync(candidate)) {
      const mod = await import(candidate);
      return mod.default || mod;
    }
  }
  const pkgAnchor = "/opt/node24/lib/node_modules/@deepseek-ai/dsh/package.json";
  if (fs.existsSync(pkgAnchor)) {
    const req = createRequire(pkgAnchor);
    return req("node-pty");
  }
  throw new Error(`node-pty not found in any expected location: ${NODE_PTY_CANDIDATES.join(", ")}`);
}

function parseArgs(argv) {
  const opts = { cols: 80, rows: 24, cwd: "/root", shell: "/bin/bash" };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--cols" && argv[i + 1]) opts.cols = Math.max(10, Number(argv[++i]) || 80);
    else if (arg === "--rows" && argv[i + 1]) opts.rows = Math.max(4, Number(argv[++i]) || 24);
    else if (arg === "--cwd" && argv[i + 1]) opts.cwd = argv[++i];
    else if (arg === "--shell" && argv[i + 1]) opts.shell = argv[++i];
  }
  return opts;
}

function sendFrame(obj) {
  process.stdout.write(`${JSON.stringify(obj)}\n`);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const dshHome = process.env.DSHN_TERMINAL_DSH_HOME || "/root/.dsh-terminal";
  fs.mkdirSync(dshHome, { recursive: true });

  const shellBin = fs.existsSync(opts.shell) ? opts.shell : "/bin/sh";
  const shellArgs = shellBin.endsWith("/bash") ? ["-il"] : ["-i"];

  const pty = await loadNodePty();

  const env = {
    ...process.env,
    HOME: process.env.HOME || "/root",
    DSH_HOME: dshHome,
    TERM: process.env.TERM || "xterm-256color",
    COLORTERM: "truecolor",
    LANG: process.env.LANG || "en_US.UTF-8",
    LC_ALL: process.env.LC_ALL || "en_US.UTF-8",
  };

  const term = pty.spawn(shellBin, shellArgs, {
    name: "xterm-256color",
    cols: opts.cols,
    rows: opts.rows,
    cwd: fs.existsSync(opts.cwd) ? opts.cwd : "/root",
    env,
  });

  sendFrame({ type: "ready", pid: term.pid, shell: shellBin, dshHome });

  term.onData((data) => {
    sendFrame({ type: "data", b64: Buffer.from(data, "utf8").toString("base64") });
  });

  term.onExit((e) => {
    sendFrame({ type: "exit", code: e.exitCode ?? 0, signal: e.signal ?? null });
    process.exit(0);
  });

  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  rl.on("line", (line) => {
    if (!line.trim()) return;
    try {
      const msg = JSON.parse(line);
      if (msg.type === "input" && typeof msg.b64 === "string") {
        term.write(Buffer.from(msg.b64, "base64").toString("utf8"));
      } else if (msg.type === "resize") {
        const cols = Math.max(10, Math.min(400, Number(msg.cols) || 80));
        const rows = Math.max(4, Math.min(200, Number(msg.rows) || 24));
        term.resize(cols, rows);
      } else if (msg.type === "kill") {
        term.kill();
      }
    } catch (err) {
      sendFrame({ type: "error", message: `Invalid control frame: ${err.message}` });
    }
  });

  rl.on("close", () => {
    try {
      term.kill();
    } catch {}
  });
}

main().catch((err) => {
  sendFrame({ type: "error", message: err?.stack || err?.message || String(err) });
  process.exit(1);
});
