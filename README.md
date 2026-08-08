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

The current beta performs structural native builds for all six fixed targets through managed Zig `0.15.2`: Windows GNU x86_64/AArch64, Linux GNU x86_64/AArch64 with a glibc 2.17 baseline, and macOS x86_64/AArch64 with minimum versions 10.15/11.0. Runtime child-JVM parity is currently exercised on the host target; non-host runtime E2E remains separate release evidence. Selected Code-bearing methods finish as either `nativeLowered` or `skipped`: helper-backed JNI execution counts as native lowering, while unsupported methods keep only their original Java body and require an explicit pre-Zig confirmation. Protection improves resistance to inspection, but it is not irreversible: runtime code must retain enough information to execute the protected program.

All schema v1 IR/LLVM protection booleans now dispatch real, bounded implementations rather than placeholder warnings. This includes program-level method inlining/splitting and IR call indirection, LLVM opaque-predicate/block/global-layout transforms, and native-registration method-table hiding. `fieldInternalization` is also implemented for a strict `private static` `boolean/byte/short/char/int/long/float/double` plus reference/array subset, but defaults to `false` because approved fields are removed from Java reflection. It normally requires `CLOSED_WORLD`; during a real build, another configured world prompts for explicit Y/N approval to analyze only references inside the current input JAR. That per-run approval does not change `worldModel`, does not scan configured classpath entries, and records the accepted external-observer boundary in the field report. Mutable primitive values use descriptor-aware atomic raw-bit storage; JVM references and arrays remain on the JVM heap in a per-defining-Class `ClassValue<Object[]>` sidecar. An additional `private static final ConstantValue` path folds eligible primitive reads into protected SSA and uses no runtime slot; zero-reference primitive/String declarations can be removed, while explicit String reads remain JVM fields to preserve identity. `ClassValue` caches the sidecar across calls; each native function activation obtains its JNI local reference lazily on the first executed access, reuses it, and releases it on exit without a native strong global reference. The pipeline can also fuse the exact non-escaping `ByteBuffer.allocate(4).putInt(i).array()` idiom into small JNI-backed native helpers, and can physically merge a strict pure-scalar single-call-site internal native method into its caller. These are still conservative subsets: inapplicable shapes are reported as skipped or retained standalone, optimized machine-code retention is not guaranteed for optimizer-sensitive transforms, and cross-linking does not imply non-host JVM runtime validation.

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
- `--debug` enables all intermediate outputs (`enabled`, debug dumps, per-class IR, LLVM, and C) and forces effective native unwind retention for that run. It remains a diagnostic mode, not a native debug-symbol build.
- Full builds show stage progress on stderr. Interactive terminals use optimized legacy regions: `Read bytecode` / `Lower to IR` / `Emit LLVM IR`, then an aggregate `Build native` bar plus one row per selected target, then `Finalize JAR`. Each target percentage is the number of completed Zig build-graph work units divided by that target's total units; large source sets are grouped deterministically into at most 64 observable compile units per target. `building` and `linking` follow real graph boundaries; during `linking`, the bar may remain at the final compilation percentage until the link finishes. A target reaches `100%` / `completed` only after its non-empty DLL/SO/dylib has been installed, and all target rows collapse immediately when the matrix completes. Normal-width terminals keep 28-character bars; narrow terminals shorten the bar before truncating useful status. Redirected output and CI receive one control-sequence-free `[current/total]` line per high-level stage without per-target log spam. Zig still receives the selected targets in one matrix-wide invocation and schedules its independent graph internally; the displayed percentages describe observable graph units, not Zig/Clang/LLVM compiler-internal progress. The `logs/zig-progress/` marker directory exists only while that invocation is running and is deleted after success, failure, or interruption.

Start with [`docs/examples/minimal-config.json`](docs/examples/minimal-config.json). Schema v1 is defined by [`docs/config.schema.json`](docs/config.schema.json); do not infer the full schema from a shortened README sample. Additional checked examples cover all-on protection, signing policies, a target matrix, and debug dumps under [`docs/examples/`](docs/examples/).

For a complete walkthrough, see [`docs/getting-started.md`](docs/getting-started.md). The authoritative input/config/output contract is [`docs/io-config-output-contract.md`](docs/io-config-output-contract.md).

### Method results

Every selected Code-bearing method receives one of two explicit results in
`reports/lowering-report.json`:

- `nativeLowered`: the original method body was replaced by a verified native implementation. LLVM, generated stubs/templates, and JVM/JNI runtime helpers are all valid implementation techniques when they do not replay a copy of the original method bytecode.
- `skipped`: the method keeps its original Java bytecode and receives no native body or `RegisterNatives` binding. Its stable reason is also written to `reports/skipped-method-report.json`.

Programmatic pipeline overloads fail closed when skipped methods exist unless the caller supplies an explicit `SkippedMethodApproval`; the report records the confirmation decision.

Abstract, already-native, and other no-Code declarations are separate selector
eligibility evidence; they do not receive a lowering status or trigger the
confirmation gate. Build failures are invocation-level diagnostics rather than
method results.

There are no silent skips and no embedded bytecode compatibility path. Before
any Zig workspace or invocation, a default build lists every skipped method and
asks `continue? (Y/N)`. Only an explicit `Y` continues; `N` or EOF stops without
writing a final JAR. Piped `Y` is supported for automation.

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

Native libraries live directly under `<workspace>/native/<library-file-name>` and are embedded in the final JAR under `<embeddedLibraryDirectory>/<library-file-name>`. The same directory contains exactly one generated Java 17 class, `<embeddedLibraryDirectory>/Loader.class`, which only handles native loading/registration and, when needed, the JVM-managed `ClassValue<Object[]>` field sidecar. It has no bytecode decoder or class-definition API. The retired `J2llFallbackSupport.class`, `J2llNativeLoaderSupport.class`, and `j2ll/generated/**/NativeLoader.class` entries are not emitted. The directory must be a canonical Java internal package path. A colliding input base or multi-release Loader entry is rejected before Zig with `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION` or `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW`. Use an application-unique value if different output artifacts can coexist in the same `ClassLoader`, because equal directories give their loaders the same binary name. Optional class-aligned IR/LLVM/C and debug artifacts live under `<workspace>/intermediates/` when enabled by config.

Start diagnosis with:

- `reports/summary.md`: short human-readable result.
- `reports/summary.json`: machine-readable aggregate.
- `reports/index.json`: paths, hashes, readiness flags, and status for generated reports.
- `reports/diagnostics.json`: stable diagnostics and remediation hints.
- `reports/lowering-report.json`: per-method lowering decisions.
- `reports/field-internalization-report.json`: hash-only field keep/internalize decisions, final paths, hybrid storage/cache/lifecycle policy, and field-removal evidence.
- `reports/packaging-report.json`: JAR preservation, signatures, Zig bootstrap, and target artifacts.
- `reports/artifact-audit.json`: final artifact, native-resource, symbol, metadata, absence-of-embedded-bytecode, and sensitive-plaintext checks.
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

当前 beta 已通过 managed Zig `0.15.2` 接实六个固定目标的结构性真实构建：Windows GNU x86_64/AArch64、Linux GNU glibc 2.17 x86_64/AArch64，以及最低版本分别为 10.15/11.0 的 macOS x86_64/AArch64。Child JVM runtime parity 当前仍在 host target 上执行；非 host runtime E2E 是独立的待补发布证据。被选中且带 Code 的方法最终只有 `nativeLowered` 或 `skipped` 两种状态：通过 JNI/runtime helper 执行仍算真实 native lowering；暂不支持的方法只保留原 Java body，并在 Zig 前要求用户明确确认。Protection 能提高分析成本，但不是不可逆保证，因为程序运行时仍必须保留执行所需的信息。

Schema v1 的 IR/LLVM protection boolean 现在都调度真实但受限的实现，不再只是 placeholder warning：包括 program-level method inline/split、IR call indirection、LLVM opaque predicate/block/global layout，以及 native registration method-table hiding。`fieldInternalization` 也已实现严格的 `private static boolean/byte/short/char/int/long/float/double` 与 reference/array 子集，但默认 `false`，因为获准字段会从 Java reflection surface 删除。它通常要求 `CLOSED_WORLD`；实际 build 遇到其他 world 时会用 Y/N 明确询问是否只分析当前输入 JAR 内的引用。本次授权不会改写 `worldModel`、不会扫描配置的 `classPath`，并会把用户接受的 external-observer 边界写入 field report。可变primitive使用descriptor-aware atomic raw-bit storage；JVM reference/array始终留在JVM heap，由per-defining-Class `ClassValue<Object[]>` sidecar持有。另一个`private static final ConstantValue`路径会把合格的primitive读取折叠进受保护SSA且不建立runtime slot；零field-reference的primitive/String声明可删除，显式String读取则因identity语义保留。`ClassValue`跨调用缓存sidecar；每个native function activation在首次实际访问时惰性获取JNI local ref，复用后在退出时释放，不建立native strong global ref。管线还可把精确且不逃逸的`ByteBuffer.allocate(4).putInt(i).array()`融合为小型JNI-backed native helper，并把严格pure-scalar、唯一call site的internal native小方法物理合并进caller。不适用shape会保守保持普通helper/standalone body，optimizer-sensitive transform不保证最终machine code稳定保留，cross-link成功也不代表非host JVM runtime已验证。

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
- `--debug` 为本次运行开启全部 intermediates（总开关、debug dumps、per-class IR、LLVM 和 C），并强制有效保留 native unwind 信息。它仍是诊断模式，不代表 native library 带调试符号。
- 完整 build 会在 stderr 显示阶段进度：交互终端采用优化后的 legacy 分阶段区域，依次显示 `Read bytecode` / `Lower to IR` / `Emit LLVM IR`、一个 `Build native` 总进度条和每个 target 独立的进度行，最后显示 `Finalize JAR`。每个 target 的百分比等于其已完成的 Zig 构建图工作单元数除以总工作单元数；大输入会被确定性分组为每 target 最多 64 个可观测编译单元。`building` 与 `linking` 来自真实图边界，进入 `linking` 后可能停在最终编译百分比，直到链接完成。只有非空 DLL/SO/dylib 安装完成后才显示 `100%` / `completed`，全部 target 完成时这些行立即折叠。正常宽度保留 28 字符进度条，窄终端先缩短进度条再截断状态。重定向输出和 CI 每个高层阶段只输出一行无控制字符的 `[current/total]` 纯文本，不刷逐 target 日志。Zig 仍通过一次 matrix-wide invocation 接收全部 target 并在内部调度独立构建节点；这里的百分比仅表示可观测构建图工作单元完成率，不代表 Zig/Clang/LLVM 编译器内部百分比。`logs/zig-progress/` marker 目录只在本次 invocation 运行期间存在，成功、失败或中断后都会删除。

请从 [`docs/examples/minimal-config.json`](docs/examples/minimal-config.json) 开始。Schema v1 的权威定义是 [`docs/config.schema.json`](docs/config.schema.json)，不要把 README 中的缩略示例当成完整 schema。[`docs/examples/`](docs/examples/) 还包含全开 protection、签名策略、target matrix 和 debug dump 配置。

完整上手流程见 [`docs/getting-started.md`](docs/getting-started.md)，输入、配置与输出的正式契约见 [`docs/io-config-output-contract.md`](docs/io-config-output-contract.md)。

### 方法结果

每个被 selector 选中且带 Code 的方法都会在
`reports/lowering-report.json` 中得到两种结果之一：

- `nativeLowered`：原 method body 已由经过验证的 native implementation 取代。LLVM、生成式 stub/template 与 JVM/JNI runtime helper 都可以参与实现，但不能重放原方法字节码副本。
- `skipped`：保留原 Java bytecode，不生成 native body，也不进入 `RegisterNatives`；稳定 reason 同时写入 `reports/skipped-method-report.json`。

程序化 pipeline 在存在 skipped method 时默认 fail closed；只有调用方显式提供 `SkippedMethodApproval` 才能批准继续，报告会记录确认决定。

abstract、already-native 和其他无 Code declaration 只作为 selector eligibility
证据记录，不产生 lowering status，也不触发确认。构建失败属于 invocation-level
diagnostic，不是方法状态。

Selector 不会被静默跳过，native artifact 中也不再嵌入兼容用字节码。默认 build
在创建任何 Zig workspace 或调用 Zig 前列出全部 skipped methods，并询问
`continue? (Y/N)`；只有显式 `Y` 继续，`N` 或 EOF 都会终止且不写 final JAR。
自动化可以通过管道传入 `Y`。

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

Native library 直接位于 `<workspace>/native/<library-file-name>`，并以 `<embeddedLibraryDirectory>/<library-file-name>` 路径嵌入 final JAR。同一目录中只生成一个 Java 17 的 `<embeddedLibraryDirectory>/Loader.class`：它只负责 native loading/registration，以及实际需要时由 JVM 管理的 `ClassValue<Object[]>` field sidecar；不包含字节码 decoder 或 class-definition API。旧 `J2llFallbackSupport.class`、`J2llNativeLoaderSupport.class` 和 `j2ll/generated/**/NativeLoader.class` 不再输出。该目录必须是规范 Java internal package path；输入 base 或 multi-release 同名 Loader 会在 Zig 前分别以 `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION` / `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW` 失败。若多个不同 output artifact 可能进入同一个 `ClassLoader`，应为每个应用选择唯一目录，因为相同目录会得到相同 loader binary name。Config 启用时，class-aligned IR/LLVM/C 与 debug artifacts 写入 `<workspace>/intermediates/`。

排查问题时优先查看：

- `reports/summary.md`：简短的人类可读摘要。
- `reports/summary.json`：机器可读汇总。
- `reports/index.json`：报告路径、hash、readiness flags 和状态。
- `reports/diagnostics.json`：稳定 diagnostic 与修复 hint。
- `reports/lowering-report.json`：逐方法 lowering 决策。
- `reports/field-internalization-report.json`：hash-only field 保留/内置决策、final path、hybrid storage/cache/lifecycle policy 和字段删除证据。
- `reports/packaging-report.json`：JAR 保留策略、签名、Zig bootstrap 和 target artifact。
- `reports/artifact-audit.json`：final artifact、native resource、symbol、metadata、无嵌入字节码副本与 sensitive plaintext 审计。
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
