# DSHN Bundled Runtime Environment Provenance

> Built: 2026-11-19 · Bundle Version: `0.3.1-u24.04.5-n24.22.3-dsh0.2.2-rc.5-acp-glibc` · Architecture: `linux/arm64` (`arm64-v8a`)

All runtime archives embedded under `app/src/main/assets/env/` are sourced from official upstream releases, verified against official SHA-256 checksums, and streamed/verified again on-device during installation (`EnvManager.installBundledEnvironment` + `TarGzExtractor`).

## Bundled Components

| Component | Version | Official Source | Compressed Size | SHA-256 |
|---|---|---|---|---|
| **Ubuntu Base Rootfs** | `24.04.5` (`noble`, `arm64`) | `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz` | 28.55 MB (`29,936,675` B) | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` |
| **Node.js LTS (Krypton)** | `v24.22.3` (`linux-arm64`) | `https://nodejs.org/dist/v24.22.3/node-v24.22.3-linux-arm64.tar.gz` | 55.36 MB (`58,051,304` B) | `261af2af250dfd7e9f25cf151d1b2713a0adf8f10a9ad116c4947f68627affe8` |
| **DeepSeek Harness (ACP Slim)** | `@deepseek-ai/dsh@0.2.2-rc.5` (`linux-arm64`, `glibc`) | `https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.2-rc.5.tgz` (`npm install --os=linux --cpu=arm64 --libc=glibc --omit=dev`) | 30.74 MB (`32,233,018` B) | `54c9cb4a8603e1b3d67fd09c0003f5a2b220b56633780a12c6902eb1ae891a40` |
| **Mozilla Root CA Bundle** | Node.js `tls.rootCertificates` (120 CAs) | Exported from official Node.js root trust store | 0.17 MB (`179,722` B) | `30b7a0242d363aa77faa2b2bb71f802c7890765e3350f8a566c4fccf4b7ad4f2` |

## Packaging, Trimming & Security Notes
- **Zero DSH source patches**: `@deepseek-ai/dsh@0.2.2-rc.5` runtime JS files and `package.json` are untouched.
- **ACP Reachability Pruning (`-79.5 MB` compressed / `-424 MB` raw)**: Computed exact transitive closure of `dsh --profile acp` + CLI/SDK/Cordis/tools/skills/MCP/PTY (414 packages kept). Trimmed 196 desktop/web/voice/office-only packages unreachable in native Android ACP mode, including `@deepseek-ai/libreoffice-kit-wasm` (145 MB), `sherpa-onnx-linux-arm64` (38.4 MB), `@deepseek-ai/dsh-experimental-inspector` (24 MB), `@deepseek-ai/dsh-client-ui-sidebar-documentpreview` (22.4 MB), `@deepseek-ai/dsh-web-frontend` (20.1 MB), `@deepseek-ai/dsh-client-ui-*`, `@deepseek-ai/dsh-cua*`, Windows PowerShell/conpty/darwin/x64/musl prebuilds, `.map`, `.d.ts`, and duplicate `@deepseek-ai/*/src` TS sources. Also hoisted `zod@4.6.6` once (eliminating 68 nested duplicates).
- **Verified `ELF-aarch64` (`0755`) glibc native modules**:
  - `node-addon-require-builtin-linux-arm64-gnu/prebuilt/linux-arm64-gnu-napi-v9.node`
  - `node-pty/prebuilds/linux-arm64/pty.node`
  - `@koromix/koffi-linux-arm64/linux_arm64/koffi.node`
  - `@deepseek-ai/node-addon-system-linux-arm64/bin/glibc/system.node` & `landlock-run`
  - `@img/sharp-linux-arm64/lib/sharp-linux-arm64-0.35.5.node` & `@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.18.7`
  - `@vscode/ripgrep-linux-arm64/bin/rg`
- **bxroot DNS compatibility**: `assets/dshn/dshn-dns-preload.cjs` (loaded via `NODE_OPTIONS=--require=/opt/dshn/dshn-dns-preload.cjs`) transparently falls back to Node c-ares + `/etc/resolv.conf` when glibc internal `getaddrinfo` returns `EAI_AGAIN` under `LD_PRELOAD` `bxroot`.
- **Uncompressed APK asset storage**: Bundled archives use `.tgz.bin` with `androidResources { noCompress += listOf("gz", "tgz", "bin") }` so Android `aapt2` stores them verbatim (`STORED`).
