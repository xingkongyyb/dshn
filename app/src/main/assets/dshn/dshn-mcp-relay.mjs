#!/opt/node24/bin/node
/**
 * Transparent stdio <-> Unix domain socket relay for Device Bridge MCP.
 * Kept intentionally dependency-free so ACP can spawn it directly inside bxroot.
 */
import net from "node:net";

const sockPath = process.env.DSHN_DEVICE_MCP_SOCK || "/run/dshn-bridge/device-mcp.sock";

const socket = net.createConnection({ path: sockPath });

socket.on("connect", () => {
  process.stdin.pipe(socket);
  socket.pipe(process.stdout);
});

socket.on("error", (err) => {
  process.stderr.write(`[dshn-mcp-relay] UDS error (${sockPath}): ${err.message}\n`);
  process.exit(1);
});

socket.on("end", () => {
  process.exit(0);
});

socket.on("close", (hadError) => {
  process.exit(hadError ? 1 : 0);
});

process.stdin.on("end", () => {
  socket.end();
});

process.on("SIGTERM", () => {
  socket.destroy();
  process.exit(0);
});

process.on("SIGINT", () => {
  socket.destroy();
  process.exit(0);
});
