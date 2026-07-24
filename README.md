# j2ll

[English](#english) | [中文](#中文)

## English

j2ll is a JVM-hosted JAR obfuscation and native-lowering tool. It rewrites selected Java methods, builds JNI dynamic libraries, and repackages everything as a runnable JAR.

The output is **not** a standalone native executable or a replacement Java runtime. It still runs on a JVM. Java objects, arrays, strings, exceptions, monitors, threads, identity, lifetime, and garbage collection remain JVM-owned; lowered code reaches them through JNI and runtime helpers.

```text
.class -> ASM -> method CFG -> hierarchy/call analysis -> SSA IR
       -> optimization/protection -> LLVM modules -> managed Zig
       -> JNI registration + embedded native libraries -> output JAR
```

The current beta performs structural native builds for all six fixed targets through managed Zig `0.15.2`: Windows GNU x86_64/AArch64, Linux GNU x86_64/AArch64 with a glibc 2.17 baseline, and macOS x86_64/AArch64 with minimum versions 10.15/11.0. Runtime child-JVM parity is currently exercised on the host target; non-host runtime E2E remains separate release evidence. JVM semantics that are not safely lowered use explicit helper-backed or bytecode-preserving fallback paths. Protection improves resistance to inspection, but it is not irreversible: runtime code must retain enough information to execute the protected program.

### Requirements

- JDK 25 to build and run the current j2ll CLI.
- Java 17 or newer to run an output JAR; the generated runtime loader is a Java 17 classfile.
- Managed Zig `0.15.2` for native builds. j2ll installs or reuses it according to the policy below.

### Build the CLI

Build the runnable JAR:

```sh
./gradlew cliJar
```

The stable artifact is:

```text
build/cli/j2ll.jar
```

Build the beta distribution, including the CLI JAR, schema, examples, sample guides, and user documentation:

```sh
./gradlew distJ2ll
```

The distribution is written to:

```text
build/dist/j2ll/
```

### CLI

Canonical syntax: `j2ll [--config <config.json>] [--validate|--dry-run] [--debug]`. Help and version remain standalone flags.

```sh
java -jar build/cli/j2ll.jar --help
java -jar build/cli/j2ll.jar --version
java -jar build/cli/j2ll.jar --validate --config <config.json>
java -jar build/cli/j2ll.jar --dry-run --config <config.json>
java -jar build/cli/j2ll.jar --config <config.json>
java -jar build/cli/j2ll.jar --debug --config <config.json>
```

- `--config` selects a config file. Without it, j2ll reads `Config.json` from the current directory.
- `--validate` checks the configuration only and creates no workspace or pipeline artifacts.
- `--dry-run` validates config and selectors and performs target preflight. It creates a report workspace, but never invokes Zig, builds native libraries, or writes a final JAR.
- With neither `--validate` nor `--dry-run`, j2ll runs the full build pipeline.
- `--debug` enables all intermediate outputs (`enabled`, debug dumps, per-class IR, LLVM, and C) for that run. It is diagnostic artifact retention, not a native debug-symbol build.
- Full builds show stage progress on stderr. Interactive terminals use optimized legacy regions: `Read bytecode` / `Lower to IR` / `Emit LLVM IR`, then an aggregate `Build native` bar plus one `building/linking` or `done` row per target and a `Stage` row, then `Finalize JAR`. Native aggregate progress advances only when a non-empty target library is installed; no compiler percentage is invented. Normal-width terminals keep 28-character bars; narrow terminals shorten the bar before truncating useful status. Redirected output and CI receive one control-sequence-free `[current/total]` line per high-level stage without per-target log spam. Zig still receives the selected targets in one invocation and schedules its independent build graph internally.

Start with [`docs/examples/minimal-config.json`](docs/examples/minimal-config.json). Schema v1 is defined by [`docs/config.schema.json`](docs/config.schema.json); do not infer the full schema from a shortened README sample. Additional checked examples cover all-on protection, signing policies, a target matrix, and debug dumps under [`docs/examples/`](docs/examples/).

For a complete walkthrough, see [`docs/getting-started.md`](docs/getting-started.md). The authoritative input/config/output contract is [`docs/io-config-output-contract.md`](docs/io-config-output-contract.md).

### Method results

Every selector match receives an explicit result in `reports/lowering-report.json`:

- `lowered`: the method was rewritten and its implementation is provided by generated native code plus JVM/JNI helpers.
- `halfLowered`: the method uses a native entry, but one or more operations use an explicit JVM fallback. In schema v1, bytecode needed by ordinary-method fallback is stored as an encoded `nativeEmbeddedClassBlob` in the native artifact, not as a plaintext generated class in the output JAR.
- `frontendSkipped`: the original bytecode remains runnable because the method shape cannot yet be safely native-wrapped. The reason is also recorded in `reports/frontend-skip-report.json`.
- `notApplicable`: the selector matched a method without a lowerable body, such as an abstract or already-native method.
- `failed`: j2ll could not preserve a safe result; the build fails and no final JAR is retained.

There are no silent selector skips. `halfLowered` and `frontendSkipped` are conservative compatibility outcomes, not equivalent to `failed`, but they usually provide less protection than a fully `lowered` method.

### Managed Zig 0.15.2

The only schema v1 native build driver is Zig `0.15.2`. Its normalized layout is next to the runnable JAR:

```text
<j2ll-home>/
  j2ll.jar
  zig/
    zig        # or zig.exe on Windows
    lib/
```

j2ll applies this resolution order:

1. Reuse `zig/zig` or `zig/zig.exe` only when its version is exactly `0.15.2`.
2. Otherwise, reuse the official current-host Zig archive if it is already beside `j2ll.jar`.
3. If no local archive exists, download it from `https://ziglang.org/download/0.15.2/`.
4. Verify local or downloaded archives against built-in official SHA-256 metadata before extraction, then normalize the extracted files into `zig/`.

An archive checksum mismatch is a native/toolchain failure and no final JAR is written. Signature verification is not currently enforced: reports explicitly record `signatureStatus=notVerifiedBoundary`. j2ll does not claim archive-signature verification.

One generated `build.zig` workspace and one matrix-wide `zig build` invocation compile and link every selected target. The fixed target queries are `x86_64-windows-gnu`, `aarch64-windows-gnu`, `x86_64-linux.3.2-gnu.2.17`, `aarch64-linux.3.7-gnu.2.17`, `x86_64-macos.10.15`, and `aarch64-macos.11.0`. This is real cross-target artifact generation, not a simulated package plan. In schema v1 every selected target is required; an actual capability, preflight, compile, or link failure reports `ZIG_TARGET_UNBUILDABLE`, exits as a toolchain failure, and does not write a final JAR.

### Workspace and reports

`--dry-run` and the default build mode create their workspace automatically under the resolved `outputDirectory`. The name is `build_yyyy-MM-dd_HH-mm-ss`; if it already exists, j2ll appends `-1`, `-2`, and so on. `--validate` creates no workspace.

A successful build writes its primary artifact to:

```text
<resolved-outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]/<input-name>.jar
```

Native libraries live directly under `<workspace>/native/<library-file-name>` and are embedded in the final JAR under `<embeddedLibraryDirectory>/<library-file-name>`. The same directory contains exactly one generated Java 17 class, `<embeddedLibraryDirectory>/Loader.class`, which always handles native loading and includes `defineHiddenFallback` only when this build actually uses `nativeEmbeddedClassBlob`. The retired `J2llFallbackSupport.class`, `J2llNativeLoaderSupport.class`, and `j2ll/generated/**/NativeLoader.class` entries are not emitted. The directory must be a canonical Java internal package path. A colliding input base or multi-release Loader entry is rejected before Zig with `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION` or `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW`. Use an application-unique value if different output artifacts can coexist in the same `ClassLoader`, because equal directories give their loaders the same binary name. Optional class-aligned IR/LLVM/C and debug artifacts live under `<workspace>/intermediates/` when enabled by config.

Start diagnosis with:

- `reports/summary.md`: short human-readable result.
- `reports/summary.json`: machine-readable aggregate.
- `reports/index.json`: paths, hashes, readiness flags, and status for generated reports.
- `reports/diagnostics.json`: stable diagnostics and remediation hints.
- `reports/lowering-report.json`: per-method lowering decisions.
- `reports/packaging-report.json`: JAR preservation, signatures, Zig bootstrap, and target artifacts.
- `reports/artifact-audit.json`: final artifact, native-resource, symbol, metadata, fallback-blob, and sensitive-plaintext checks.
- `reports/release-readiness.json`: readiness checks and missing evidence.
- `reports/failure-report.json`: primary stage/reason on failed runs; `finalArtifactWritten=false`.

CLI stdout stays short and points to these files; full-build progress and failures use stderr. Failed config, frontend, native, signing, audit, or readiness runs retain reports but do not retain a success-state final JAR.

### Common Gradle commands

```sh
./gradlew cliJar          # build build/cli/j2ll.jar
./gradlew distJ2ll        # build build/dist/j2ll/
./gradlew test            # unit and integration suite
./gradlew betaAcceptance  # exercise the distribution JAR end to end
./gradlew clean build     # clean build plus verification
```

Support boundaries and internal design are documented separately:

- [Java/JVM support tiers](docs/java-support-tiers.md)
- [Protection and obfuscation](docs/protection-obfuscation.md)
- [Compiler pipeline guides](docs/pipeline/README.md)
- [Clean-room rewrite roadmap](docs/rewrite-roadmap.md)

## 中文

j2ll 是一个 **JVM-hosted JAR 混淆与 native lowering 工具**。它会改写选中的 Java 方法、构建 JNI 动态库，再把类、资源、loader 和 native library 重新打包成可运行 JAR。

输出产物不是独立 native executable，也不是替代 JVM 的 Java runtime；它仍然必须运行在 JVM 上。Java object、array、String、Throwable、monitor、Thread、对象身份、生命周期和 GC 都由 JVM 管理，lowered code 只能通过 JNI 与 runtime helper 操作这些值。

```text
.class -> ASM -> method CFG -> hierarchy/call analysis -> SSA IR
       -> optimization/protection -> LLVM modules -> managed Zig
       -> JNI registration + embedded native libraries -> output JAR
```

当前 beta 已通过 managed Zig `0.15.2` 接实六个固定目标的结构性真实构建：Windows GNU x86_64/AArch64、Linux GNU glibc 2.17 x86_64/AArch64，以及最低版本分别为 10.15/11.0 的 macOS x86_64/AArch64。Child JVM runtime parity 当前仍在 host target 上执行；非 host runtime E2E 是独立的待补发布证据。暂时不能安全 native lowering 的 JVM 语义会进入明确的 helper 或 bytecode-preserving fallback。Protection 能提高分析成本，但不是不可逆保证，因为程序运行时仍必须保留执行所需的信息。

### 环境要求

- 使用 JDK 25 构建并运行当前 j2ll CLI。
- 输出 JAR 需要 Java 17 或更新版本；生成的 runtime loader 是 Java 17 classfile。
- Native build 固定使用 managed Zig `0.15.2`。

### 构建 CLI

```sh
./gradlew cliJar
```

可运行 JAR 的稳定路径是：

```text
build/cli/j2ll.jar
```

生成包含 CLI、schema、examples、samples 和用户文档的 beta distribution：

```sh
./gradlew distJ2ll
```

输出目录是：

```text
build/dist/j2ll/
```

### 命令行

标准语法为 `j2ll [--config <config.json>] [--validate|--dry-run] [--debug]`；help 与 version 保留为独立 flag。

```sh
java -jar build/cli/j2ll.jar --help
java -jar build/cli/j2ll.jar --version
java -jar build/cli/j2ll.jar --validate --config <config.json>
java -jar build/cli/j2ll.jar --dry-run --config <config.json>
java -jar build/cli/j2ll.jar --config <config.json>
java -jar build/cli/j2ll.jar --debug --config <config.json>
```

- `--config` 指定 config 文件；未传时默认读取当前目录的 `Config.json`。
- `--validate` 只校验 config，不创建 workspace 或 pipeline artifact。
- `--dry-run` 校验 config/selector 并执行 target preflight；它会创建报告 workspace，但不会调用 Zig、构建 native library 或写 final JAR。
- 未传 `--validate` 或 `--dry-run` 时，默认运行完整 build pipeline。
- `--debug` 为本次运行开启全部 intermediates（总开关、debug dumps、per-class IR、LLVM 和 C）。它用于保留诊断产物，不代表 native library 带调试符号。
- 完整 build 会在 stderr 显示阶段进度：交互终端采用优化后的 legacy 分阶段区域，依次显示 `Read bytecode` / `Lower to IR` / `Emit LLVM IR`、一个 `Build native` 总进度条加每个 target 独立的 `building/linking` 或 `done` 行及 `Stage` 行，最后显示 `Finalize JAR`。只有检测到对应 target 的非空动态库已经落盘时，总进度才会推进，不伪造编译百分比。正常宽度保留 28 字符进度条，窄终端先缩短进度条再截断状态。重定向输出和 CI 每个高层阶段只输出一行无控制字符的 `[current/total]` 纯文本，不刷逐 target 日志。Zig 仍通过一次 invocation 接收全部 target，并在内部调度独立构建节点。

请从 [`docs/examples/minimal-config.json`](docs/examples/minimal-config.json) 开始。Schema v1 的权威定义是 [`docs/config.schema.json`](docs/config.schema.json)，不要把 README 中的缩略示例当成完整 schema。[`docs/examples/`](docs/examples/) 还包含全开 protection、签名策略、target matrix 和 debug dump 配置。

完整上手流程见 [`docs/getting-started.md`](docs/getting-started.md)，输入、配置与输出的正式契约见 [`docs/io-config-output-contract.md`](docs/io-config-output-contract.md)。

### 方法结果

每个 selector 命中的方法都会在 `reports/lowering-report.json` 中得到明确结果：

- `lowered`：方法已 rewrite，具体实现由生成的 native code 与 JVM/JNI helper 提供。
- `halfLowered`：方法使用 native entry，但至少一个 operation 显式回到 JVM 执行。Schema v1 会把 ordinary-method fallback 所需 bytecode 作为编码后的 `nativeEmbeddedClassBlob` 放进 native artifact，不会把明文 generated fallback class 写进 output JAR。
- `frontendSkipped`：当前 method shape 还不能安全 native-wrap，因此保留可运行的原始 bytecode；原因也会进入 `reports/frontend-skip-report.json`。
- `notApplicable`：selector 命中了没有可 lower body 的方法，例如 abstract 或 already-native method。
- `failed`：j2ll 无法产生安全结果；构建失败，不保留 final JAR。

Selector 不会被静默跳过。`halfLowered` 和 `frontendSkipped` 是保守兼容结果，不等同于失败，但保护强度通常低于完整 `lowered`。

### Managed Zig 0.15.2

Schema v1 唯一的 native build driver 是 Zig `0.15.2`，其规范化目录位于可执行 JAR 同级：

```text
<j2ll-home>/
  j2ll.jar
  zig/
    zig        # Windows 为 zig.exe
    lib/
```

j2ll 按以下顺序处理 Zig：

1. 只在 `zig/zig(.exe)` 的版本恰好为 `0.15.2` 时复用现有安装。
2. 否则先查找 `j2ll.jar` 同目录下当前 host 对应的官方 Zig archive。
3. 本地 archive 不存在时，从 `https://ziglang.org/download/0.15.2/` 下载。
4. 本地或下载的 archive 都必须先按内置 Zig 官方 SHA-256 metadata 校验，通过后才解压并规范化到 `zig/`。

SHA-256 mismatch 属于 native/toolchain failure，不会写 final JAR。当前没有强制 archive signature verification；报告会明确写 `signatureStatus=notVerifiedBoundary`，j2ll 不会宣称已经完成 archive 签名验证。

所有 selected targets 由同一个生成的 `build.zig` workspace 和一次 matrix-wide `zig build` 调用编译、链接。固定 Zig target query 为 `x86_64-windows-gnu`、`aarch64-windows-gnu`、`x86_64-linux.3.2-gnu.2.17`、`aarch64-linux.3.7-gnu.2.17`、`x86_64-macos.10.15` 和 `aarch64-macos.11.0`；这是实际 cross-target artifact 构建，不是只生成 package plan。Schema v1 中每个 selected target 都是 required；实际 capability/preflight/compile/link 失败仍报告 `ZIG_TARGET_UNBUILDABLE`、按 toolchain failure 退出，并且不写 final JAR。

### Workspace 与报告

`--dry-run` 和默认 build 模式会自动在 resolved `outputDirectory` 下创建 workspace，名称为 `build_yyyy-MM-dd_HH-mm-ss`；若同名目录已经存在，则依次追加 `-1`、`-2`。`--validate` 不创建 workspace。

成功构建的主产物位于：

```text
<resolved-outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]/<input-name>.jar
```

Native library 直接位于 `<workspace>/native/<library-file-name>`，并以 `<embeddedLibraryDirectory>/<library-file-name>` 路径嵌入 final JAR。同一目录中只生成一个 Java 17 的 `<embeddedLibraryDirectory>/Loader.class`：它始终负责 native loading，只有本次构建实际使用 `nativeEmbeddedClassBlob` 时才包含 `defineHiddenFallback`；旧 `J2llFallbackSupport.class`、`J2llNativeLoaderSupport.class` 和 `j2ll/generated/**/NativeLoader.class` 不再输出。该目录必须是规范 Java internal package path；输入 base 或 multi-release 同名 Loader 会在 Zig 前分别以 `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION` / `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW` 失败。若多个不同 output artifact 可能进入同一个 `ClassLoader`，应为每个应用选择唯一目录，因为相同目录会得到相同 loader binary name。Config 启用时，class-aligned IR/LLVM/C 与 debug artifacts 写入 `<workspace>/intermediates/`。

排查问题时优先查看：

- `reports/summary.md`：简短的人类可读摘要。
- `reports/summary.json`：机器可读汇总。
- `reports/index.json`：报告路径、hash、readiness flags 和状态。
- `reports/diagnostics.json`：稳定 diagnostic 与修复 hint。
- `reports/lowering-report.json`：逐方法 lowering 决策。
- `reports/packaging-report.json`：JAR 保留策略、签名、Zig bootstrap 和 target artifact。
- `reports/artifact-audit.json`：final artifact、native resource、symbol、metadata、fallback blob 与 sensitive plaintext 审计。
- `reports/release-readiness.json`：readiness 检查与缺失证据。
- `reports/failure-report.json`：失败时的主要 stage/reason，且 `finalArtifactWritten=false`。

CLI stdout 只给出稳定摘要和这些报告路径；完整 build 的进度及失败信息写到 stderr。Config、frontend、native、signing、audit 或 readiness 失败时保留报告，但不保留成功态 final JAR。

### 常用 Gradle 命令

```sh
./gradlew cliJar          # 构建 build/cli/j2ll.jar
./gradlew distJ2ll        # 构建 build/dist/j2ll/
./gradlew test            # unit 与 integration suite
./gradlew betaAcceptance  # 使用 distribution JAR 做端到端验收
./gradlew clean build     # clean build 与验证
```

更多文档：

- [Java/JVM support tiers](docs/java-support-tiers.md)
- [Protection 与 obfuscation](docs/protection-obfuscation.md)
- [Compiler pipeline guides](docs/pipeline/README.md)
- [Clean-room rewrite roadmap](docs/rewrite-roadmap.md)
