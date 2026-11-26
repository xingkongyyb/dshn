# Third-Party Notices

This repository contains the DSHN Android application source code licensed under the **MIT License** ([LICENSE](LICENSE)). The application also integrates third-party open-source dependencies and runtime components listed below.

## 1. Vendored Runtime Components

### bxroot (v0.2.5)
- **Repository**: https://github.com/qiannianhuanxiang/bxroot
- **License**: MIT License
- **Files**: `app/src/main/jniLibs/arm64-v8a/libbxroot*.so`
- **Provenance**: Verified release tarball and SHA-256 checksums documented in `vendor/bxroot/PROVENANCE.md` and `vendor/bxroot/SHA256SUMS.txt`.

### DSH Sugar (`profile-resolution` startup optimization & Android 14+ Virtual Display agent design)
- **Repository**: https://github.com/xiaoxiao44443/DSH-Sugar
- **License**: MIT License (`Copyright (c) 2026 xiaoxiao44443 and DSH Sugar contributors`)
- **Usage**: Idempotent `compileResolution` / `moved.forget` fast-path patch adapted in `EnvManager.patchDshBootResolutionSource`, and Android 14+ `DisplayManager`/`UiAutomation`/`InputManager` virtual display helper architecture adapted in `dev.dshn.app.bridge.virtualdisplay.VirtualDisplayAgent`.

## 2. Container Rootfs & Prebuilt Runtimes (Distributed Separately / Downloaded at Runtime)

DSHN runs inside a user-space **Ubuntu 24.04.5 LTS (Noble Numbat)** ARM64 container rootfs together with **Node.js v24** and `@deepseek-ai/dsh`.

- **Ubuntu Base System (`ubuntu-base` 24.04.5 LTS aarch64)**
  - Includes GNU/Linux packages licensed under GPLv2, GPLv3, LGPLv2.1/v3, MIT, BSD, Apache-2.0, and MPL.
  - **GPL / LGPL Written Source Offer**: Complete corresponding source code for all GPL/LGPL packages included in the Ubuntu 24.04.5 LTS ARM64 rootfs is published upstream by Canonical/Ubuntu Launchpad (`https://launchpad.net/ubuntu/noble`) and Ubuntu Ports archives (`https://ports.ubuntu.com/`). If you receive a packaged binary/rootfs image built by this project, complete corresponding machine-readable source code archives for the exact shipped package versions are available on request for at least three years following distribution via GitHub Issues / Releases on the repository hosting DSHN.
- **Node.js (`node-v24.19.0-linux-arm64`)**: MIT License (includes V8, libuv, OpenSSL, zlib, etc.).
- **@deepseek-ai/dsh & bundled Node dependencies** (including `node-pty` [MIT], `@agentclientprotocol/sdk`): See respective `package.json` / `LICENSE` files inside the DSH installation.

## 3. Android & Kotlin Libraries (Gradle Maven Dependencies)

- **AndroidX (`core-ktx`, `activity-compose`, `lifecycle-*`, `compose-*`, `material3`)**: Apache License 2.0
- **Kotlin Standard Library & `kotlinx.coroutines` & `kotlinx.serialization`**: Apache License 2.0
- **JUnit 4 (`junit:junit:4.13.2`)**: Eclipse Public License 1.0 (test scope only)
