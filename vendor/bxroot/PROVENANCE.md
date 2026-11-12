# bxroot vendored 来源与安全审查记录

## 来源
- 项目：https://github.com/qiannianhuanxiang/bxroot （MIT 许可）
- 版本：**v0.2.5**（release 发布于 2026-11-03）
- 下载 URL：https://github.com/qiannianhuanxiang/bxroot/releases/download/v0.2.5/bxroot-v0.2.5-android-arm64.tar.gz
- 官方校验文件：https://github.com/qiannianhuanxiang/bxroot/releases/download/v0.2.5/SHA256SUMS.txt
- 审查日期：2026-11-12

## 完整性（双重校验）
- 外层 tarball SHA256：`58fa3cb280233e7e31350ffab33922aa5b4eb79d57cb2e75885feb55b5eacfd6`
  - 与官方 SHA256SUMS.txt **逐字匹配**；大小 842279 B 与 release 元数据一致
- 解包后内部 SHA256SUMS：8 个原生件 `sha256sum -c` **全部 OK**

## 包体审查
- 内容：8 个原生件 + docs（LICENSE/README/CHANGELOG）+ 构建元数据（VERSION/BUILD-NOTES/BUILD-METADATA/FILE-TYPES.txt/SHA256SUMS）
- **无可疑额外文件**（无脚本、无意外二进制）
- 架构：全部 **ELF 64-bit ARM aarch64**（llvm-readelf 确认）
- 页对齐：LOAD 段 Align = **0x4000（16KB）**，满足 Android 15+ 16KB 页设备要求
- 构建 profile：**default**（copyout_verified=0, claim_verified=0，SysV SHM 新建保守关闭，与上游 README 一致）

## 产物清单（→ app/src/main/jniLibs/arm64-v8a/）
| 文件 | 字节 | 类型 | 来源路径 |
|---|---|---|---|
| libbxroot.so | 715176 | 静态可执行（launcher，伪装 .so） | bin/libbxroot.so |
| libbxroot-runtime.so | 397536 | 共享库（LD_PRELOAD 钩子主体） | lib/ |
| libbxroot-ulx.so | 691440 | static-pie（自研 ELF 加载器） | lib/ |
| libbxroot-linker.so | 18928 | 共享库 | lib/ |
| libbxroot-bridge.so | 19608 | 共享库（宿主/客户桥接） | lib/ |
| libbxroot-stub-loader.so | 19848 | 共享库（静态二进制 PT_INTERP 修补） | lib/ |
| libbxroot-enter.so | 37760 | PIE（native v3 guest 回入口） | bin/ |
| libbxroot-host.so | 8240 | 静态可执行（bx-host，重命名 .so 以便打包） | bin/bx-host |

## 审查结论
**通过**。来源官方、完整性双重校验、纯 AArch64、16KB 对齐、保守 profile、无可疑内容。
bxroot 是用户态兼容运行时，**不是安全沙箱**（上游 README 明示）；威胁模型见设计文档 §8（agent 不可信、密钥出 bind 集）。

## 升级约定
vendored 锁定 v0.2.5；升级须重新走本审查流程（官方 release + SHA256 + 包体 + 对齐），并更新本记录。