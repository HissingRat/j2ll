# Input, Config, And Output Contract

本文档定义 rewrite 后 j2ll 的输入、`config.json`、输出 jar、动态链接库布局和中间产物布局。实现时以本文档为用户可见契约；内部 pipeline 文档只解释如何达成这个契约。

## Input

j2ll 的主输入是一个 Java JAR 文件。

要求：

- 输入路径由 `config.json` 的 `jarFile` 指定。
- 输入文件必须是可读取的 `.jar`。
- JAR 内的 class entry 使用 JVM internal class path，例如 `com/example/Foo.class`。
- 非 class resource 原样进入 repack 流程，除非后续 packaging policy 明确排除。

## Lowering Selection And Skip Contract

配置选中的 native lowering 范围必须全部有明确结果。j2ll 第一版采用可 skip 语义：命中的 class/method 不保证一定完成 full native lowering，但必须在 lowering report 中明确记录为 `lowered`、`halfLowered`、`frontendSkipped`、`notApplicable` 或 `failed`；其中 `frontendSkipped` 还必须进入 frontend skip report，不能静默跳过。

定义：

- `whiteList` 为空：所有未被 `blackList` 排除的可处理 class/method 都进入 requested lowering set。
- `whiteList` 非空：`whiteList` 命中的 class/method 先做 method eligibility 判定；有可改写方法体的 method 进入 requested lowering set。
- `blackList` 总是从 requested lowering set 中排除 class/method。
- class entry 命中时，该 class 中所有有方法体且可改写的 method 都进入 requested lowering set，除非被 `blackList` 排除。
- method entry 命中时，只考虑该 method；如果它有可改写方法体则进入 requested lowering set，否则记录为 `notApplicable`。
- selector 命中但没有可改写方法体的 method 记录为 `notApplicable`，不进入 native lowering。

保证：

- requested lowering set 中的每个 method 都必须产生稳定、可解释的结果。
- `lowered` method 必须完成 native lowering、rewritten bytecode、native registration 和 selected target native build。
- `halfLowered` method 必须完成 native entry lowering 和 native registration，但至少一个 operation 或 call site 通过 JVM helper fallback 执行。它必须产生明确 warning，不导致构建失败。
- `frontendSkipped` method 保留原始 bytecode，并在 `reports/lowering-report.json` 中记录 skip stage、reason code、human-readable reason 和是否影响调用方 lowering。
- `notApplicable` method 是 selector 命中但不需要或不能做 native body rewrite 的方法，例如 abstract method、already-native method、没有 Code attribute 的 interface method 或 annotation element。它不是失败，也不是 `frontendSkipped`。
- `failed` 表示无法安全保留语义或无法继续构建；只要 requested lowering set 中出现 `failed`，整个构建失败。
- excluded method 可以保留为原始 bytecode。
- 不允许静默 skip。任何 skip 都必须出现在 frontend skip report 和 diagnostics 中。

典型 `frontendSkipped` 原因：

- unsupported classfile version、preview feature 或 malformed-but-loadable attribute。
- unsupported bytecode opcode、stack map pattern、exception shape、monitor shape。
- 某个 feature 没有硬依赖缺失，但当前 method shape 暂不适用。
- selector 命中的 method 与当前 tier gate 不匹配。

缺少 enabled analysis/protection pass 声明的硬依赖，例如 `classPath`、JDK runtime metadata 或 native toolchain capability，不属于普通 skip；它是 config/preflight error，j2ll 必须提示补齐输入或关闭对应 feature 后退出。

构建失败时：

- 不输出成功态 final jar。
- 输出 diagnostics、resolved config、lowering report 和已生成的 debug artifacts。

## Config File

默认配置文件名是：

```text
config.json
```

命令行可以指定其他路径。配置中的相对路径以 config 文件所在目录为基准解析。

### Example

```json
{
  "schemaVersion": 1,
  "jarFile": "/absolute/path/to/input.jar",
  "classPath": [],
  "javaHome": null,
  "runtimeImage": null,
  "worldModel": "PARTIAL_WORLD",
  "javaSupportTier": "TIER_5",
  "fallbackMode": "nativeEmbeddedClassBlob",
  "outputDirectory": "out",
  "whiteList": [],
  "blackList": [],
  "target": {
    "windowsX64": true,
    "windowsArm64": false,
    "linuxX64": true,
    "linuxArm64": false,
    "macosX64": true,
    "macosArm64": true
  },
  "libraryName": null,
  "embeddedLibraryDirectory": "native0",
  "signaturePolicy": "fail",
  "signing": null,
  "intermediates": {
    "enabled": true,
    "includeDebugDumps": true,
    "includePerClassIr": true,
    "includePerClassLlvm": true,
    "includePerClassC": true
  },
  "protection": {
    "enabled": true,
    "seed": null,
    "ir": {
      "enabled": true,
      "controlFlowFlattening": { "enabled": true },
      "fakeBranches": { "enabled": true },
      "basicBlockSplitting": { "enabled": true },
      "constantEncryption": { "enabled": true },
      "stringEncryption": { "enabled": true },
      "methodInlining": { "enabled": true },
      "methodSplitting": { "enabled": true },
      "callIndirection": { "enabled": true },
      "methodTableHiding": { "enabled": true }
    },
    "llvm": {
      "enabled": true,
      "nameObfuscation": { "enabled": true },
      "opaquePredicates": { "enabled": true },
      "blockLayoutPerturbation": { "enabled": true },
      "indirectCalls": { "enabled": true },
      "globalLayout": { "enabled": true },
      "visibilityHardening": { "enabled": true }
    },
    "binary": {
      "enabled": true,
      "hideInternalSymbols": true,
      "strip": true,
      "removePdb": true,
      "symbolAudit": true
    }
  }
}
```

### Top-Level Fields

除明确标注有默认值的字段外，schema 声明的 fields 都必须出现在 `config.json` 中，包括 nested config object 的 fields。允许为 `null` 的字段会在下文明确写出；除此之外，缺字段是 config error，j2ll 在进入主 pipeline 前退出。schema v1 的 `target` 是当前唯一可省略 top-level object；省略时 resolved config 使用当前 host target。

未知字段策略：

- 未知 top-level field 或未知 nested field 会产生 warning，但不会阻止构建。
- warning 必须写入 `reports/diagnostics.json` 和 release suite diagnostics evidence；已解析配置仍写入 `config.resolved.json`。
- 未知字段不会参与 resolved config、selector、seed 或 artifact hash 计算。

### CLI Exit Codes And Console Output

The user-facing config schema lives at `docs/config.schema.json`. Schema v1 also ships examples under `docs/examples/`: `minimal-config.json`, `protection-all-on-config.json`, `signed-strip-resign-config.json`, `target-matrix-config.json` and `debug-dumps-config.json`. The JSON Schema is append-compatible and permits unknown future fields; the runtime loader still reports unknown fields as warnings.

From source, the beta CLI artifact is built with:

```bash
bash ./gradlew cliJar
```

The runnable artifact path is stable: `build/cli/j2ll.jar`. The jar manifest points at the CLI main class, so user smoke commands are:

```bash
java -jar build/cli/j2ll.jar --help
java -jar build/cli/j2ll.jar --version
java -jar build/cli/j2ll.jar validate docs/examples/minimal-config.json
```

For a beta distribution directory, build:

```bash
bash ./gradlew distJ2ll
```

The distribution is written to `build/dist/j2ll/` and contains `j2ll.jar`, `docs/examples`, `docs/samples`, `docs/getting-started.md`, `docs/config.schema.json` and the I/O contract docs. It intentionally does not vendor a Zig archive; first run follows the managed Zig bootstrap policy below.

The beta acceptance command is:

```bash
bash ./gradlew betaAcceptance
```

It uses the distribution JAR, not the test classpath, to run help/version, validation, dry-run, sample build, child JVM differential and report/readiness/privacy checks.

CLI commands:

- `j2ll --help`
- `j2ll --version`
- `j2ll validate <config.json>`
- `j2ll dry-run <config.json> <workspace>`
- `j2ll build <config.json> <workspace>`

`validate` only checks config and does not create pipeline artifacts. `dry-run` writes reports for config, selector expansion and target preflight, but never invokes managed Zig/native build and never writes a final JAR.

`j2ll build <config.json> <workspace>` and the failure-producing commands use stable exit codes:

- `0`: success.
- `2`: config validation failure.
- `3`: frontend, parse, CFG, lowering, validation or LLVM emission failure.
- `4`: toolchain, native build or symbol audit failure.
- `5`: packaging or signing failure.
- `6`: artifact audit failure.
- `7`: strict release-readiness failure.
- `1`: unexpected internal error or an uncategorized fatal diagnostic.

On success stdout is intentionally short and includes only the final output JAR path, reports directory, summary report path and report index path. Dry-run success prints `dryRunReport=...`, `reportsDir=...`, `summaryReport=...` and `reportIndex=...`. On failure stderr includes the primary human-readable failure, one short `hint=...` line when available, reports directory, summary report path and report index path. Detailed diagnostics remain in `reports/*.json`; CLI output must not dump long JSON bodies. Release-readiness failures additionally print `releaseReadinessReport=<path>` and at most the top three `missingEvidence` entries from `reports/release-readiness.json`.

Minimal command:

```bash
j2ll build config.json /tmp/j2ll-workspace
```

On success stdout contains stable `outputJar=...`, `reportsDir=...`, `summaryReport=...` and `reportIndex=...` lines. On config/toolchain/signing/artifact-audit/readiness failure the final JAR is not retained; inspect `reports/index.json`, `reports/summary.md`, `reports/summary.json`, `reports/diagnostics.json`, `reports/failure-report.json` and the stage-specific report named by stderr.

### Zig Toolchain Layout

Schema v1 uses managed Zig `0.15.2` only. The executable lives beside the runnable `j2ll.jar`:

```text
<j2ll-home>/
  j2ll.jar
  zig/
    zig
```

On startup j2ll first reuses `zig/zig(.exe)` if it reports exactly `0.15.2`. If the executable is missing or has the wrong version, j2ll looks for the official current-host Zig archive in `<j2ll-home>`. A local archive is verified against built-in official Zig `0.15.2` SHA-256 metadata before extraction. If no local archive exists, j2ll downloads from `https://ziglang.org/download/0.15.2/`, verifies the downloaded archive SHA-256, and only then normalizes the extracted official directory into `zig/`. SHA mismatch fails the native/toolchain stage and must not fall back to using the archive or writing a final JAR. The extraction path rejects archive entries that escape the destination. Signature verification is not yet enforced; reports must say `signatureStatus=notVerifiedBoundary` rather than silently claiming full signature verification.

`packaging-report.json` records managed Zig path/version, `build.zig`, target preflight/package plan, build command, selected target artifacts and `bootstrapEvents` such as `FOUND_MANAGED_ZIG`, `WRONG_VERSION_REINSTALL`, `LOCAL_ARCHIVE_USED`, `DOWNLOAD_ATTEMPTED`, `ARCHIVE_CHECKSUM_VERIFIED` and `INSTALLED_MANAGED_ZIG`. Archive-related events include `archiveName`, `archiveSha256`, `checksumStatus`, `signatureStatus` and `source` (`existingInstall`, `localArchive` or `download`). Dry-run reports target preflight/package plan without invoking Zig.

`schemaVersion`

Config schema version. Required. 第一版固定为 integer `1`。不支持的 schema version 是 config error。

`jarFile`

Input JAR path. Required. Relative paths are resolved from the config file directory.

`classPath`

Additional classpath entries used for hierarchy, call graph, runtime analysis and protection passes that need whole-program context. Required field; use `[]` when no extra classpath is provided. Entries may be JAR files or directories and are resolved from the config file directory.

如果 enabled analysis/protection pass 声明需要 `classPath`，但 resolved `classPath` 为空或无法解析其必需 type，j2ll 必须在 config/preflight 阶段报错并退出。错误信息必须说明：补充 `classPath`，或关闭对应 analysis/protection feature。

`javaHome`

Optional JDK home used to locate Java runtime metadata and tools. Required field; may be `null`. When non-null, it must point to a readable JDK/JRE home.

`runtimeImage`

Optional Java runtime image path, for example a `jimage`/`modules` source used by future JDK metadata import. Required field; may be `null`. If both `javaHome` and `runtimeImage` are `null`, stages that require JDK metadata must fail preflight with a clear diagnostic.

`worldModel`

Analysis world model. Required. Allowed values:

- `CLOSED_WORLD`
- `PARTIAL_WORLD`
- `JDK_EXTERNAL_WORLD`
- `UNKNOWN_DYNAMIC_WORLD`

Recommended default is `PARTIAL_WORLD`: application classes from the input JAR are analyzed directly, `classPath` is used when present, and missing external/library facts stay conservative. Any analysis/protection pass that requires a stronger world model must declare that requirement in preflight; if the configured world model, `classPath` or JDK metadata cannot satisfy it, j2ll exits with a clear error telling the user to provide the missing input or disable that feature.

World model validation matrix:

| Value | Minimum required inputs | Main consequence |
| --- | --- | --- |
| `CLOSED_WORLD` | input JAR, complete `classPath`, JDK metadata from `javaHome` or `runtimeImage`, and no enabled dynamic-loading escape hatch | Historical wire name for a complete JVM classpath analysis assumption. Allows aggressive devirtualization and method table hiding while the output still runs on the JVM. Missing class metadata is a preflight error. |
| `PARTIAL_WORLD` | input JAR; optional `classPath` and JDK metadata | Recommended default. Missing external metadata is allowed but produces conservative external nodes and fallback-friendly analysis. |
| `JDK_EXTERNAL_WORLD` | input JAR plus enough JDK identity metadata to classify JDK classes | Application classes are analyzed; JDK methods mostly use intrinsics, runtime helpers or JVM helper fallback. |
| `UNKNOWN_DYNAMIC_WORLD` | input JAR only | Reflection, custom classloaders and generated classes are assumed possible. Analysis must stay conservative and avoid protection decisions that require a complete classpath. |

`javaSupportTier`

Maximum Java support tier requested by the config. Required. Allowed values are `TIER_0` through `TIER_5`; see `docs/java-support-tiers.md`.

This field is a feature gate and diagnostics policy, not a marketing claim. If a method requires a feature above the configured tier, it is reported as `frontendSkipped` with a tier reason. If a feature is inside the configured tier but not implemented yet, the method may become `frontendSkipped`, `halfLowered` through JVM helper fallback, or `failed` depending on whether j2ll can preserve runnable semantics.

`fallbackMode`

JVM helper fallback body storage strategy. Required. Schema version 1 supports:

- `nativeEmbeddedClassBlob`: primary strategy. Fallback bytecode needed by `halfLowered` methods is compressed/encrypted into the selected native libraries. At runtime the native/JVM helper layer decrypts it, defines a hidden or generated helper class for the current classloader, and invokes that helper through JNI or a Java bootstrap method.

Intentionally not supported in schema version 1:

- `generatedClass`: do not emit a plain generated fallback class containing original method bodies in the output JAR.
- No other fallback modes are defined. Unknown fallback modes are config errors.

`outputDirectory`

Directory for build workspaces. Required. Each run creates a timestamped workspace under this directory.

`whiteList`

List of class or method selectors that define the requested lowering set. Empty list means all non-blacklisted eligible classes/methods are requested.

Selector forms:

```text
<class>
<class>#<method name>!<method descriptor>
```

Examples:

```json
[
  "mypackage/myotherpackage/Class1",
  "mypackage/myotherpackage/Class1#doSomething!()V",
  "mypackage/myotherpackage/Class1$SubClass#doOther!(I)V"
]
```

Wildcard examples:

```json
[
  "mypackage/myotherpackage/*",
  "mypackage/myotherpackagewithnested/**",
  "mypackage/myotherpackage/*/Class1",
  "mypackage/myotherpackagewithnested/**/Class1",
  "mypackage/myotherpackage/Class*"
]
```

Wildcard rules:

- Class names use JVM internal names with `/`; do not include `.class`.
- Nested classes use normal JVM `$` syntax, for example `Outer$Inner`.
- A selector must match the whole internal class name, not a substring.
- `*` inside one path segment matches zero or more characters except `/`.
- `*` as a whole segment matches exactly one path segment.
- `**` must be a whole segment and matches zero or more path segments.
- `Class*` matches classes whose final segment starts with `Class`.
- Method descriptors use exact JVM method descriptor syntax, for example `()V` or `(I)Ljava/lang/String;`.
- `<init>` and `<clinit>` are valid method names.
- Method selector descriptors are required. `Foo#doWork` is invalid because overloads would be ambiguous.
- Schema version 1 supports wildcards only in the class portion. Method name and descriptor are exact when `#...!` is present.

Selector behavior:

- Invalid selector syntax is a config error.
- A `whiteList` selector that matches no class/method is a config error.
- A `blackList` selector that matches no class/method emits a warning and continues.
- `blackList` wins over `whiteList` after both are expanded.
- If a selector matches abstract methods, already-native methods, interface methods without Code, or annotation elements without Code, those methods are recorded as `notApplicable`.
- If a class selector matches an interface, interface methods with Code such as default, static or private methods are selected and use the interface method stub rewrite strategy.
- Bridge, synthetic, enum-generated and record-generated methods are eligible when they have Code. Their flags are recorded in sidecar reports for audit and tests; the flags do not by themselves skip lowering.

`blackList`

List of class or method selectors excluded from native lowering. `blackList` overrides `whiteList`.

`target`

Target dynamic library matrix. If the field is absent, schema v1 defaults to the current host target detected from the running JVM. If the field is present, all target booleans below are required and at least one target must be true. Every explicitly selected target is required in schema v1; if preflight cannot build it, the pipeline fails and does not write the final output JAR.

Fields:

- `windowsX64`: build x86_64 Windows DLL.
- `windowsArm64`: build AArch64 Windows DLL.
- `linuxX64`: build x86_64 Linux shared object.
- `linuxArm64`: build AArch64 Linux shared object.
- `macosX64`: build x86_64 macOS dylib.
- `macosArm64`: build AArch64 macOS dylib.

`libraryName`

Optional logical native library base name. If `null`, j2ll generates a deterministic name from the input artifact and resolved config. This name is used for native build metadata and loader metadata. Selected target libraries are still embedded into the output jar under `embeddedLibraryDirectory`.

`embeddedLibraryDirectory`

Package path inside the output jar where selected target dynamic libraries are stored. Default recommendation is `native0`. The path must be a relative JAR path and must not start with `/`.

### Managed Zig Toolchain

Schema version 1 不暴露 native toolchain 配置。j2ll 固定使用 Zig `0.15.2` 作为唯一 native build driver。

Toolchain home：

```text
<j2ll-home>/
  j2ll.jar
  <zig-archive>
  zig/
    zig
    zig.exe
    lib/
```

`<j2ll-home>` 是可执行 `j2ll.jar` 所在目录。生产运行时，Zig 解压目录固定为 `<j2ll-home>/zig`，Zig executable 固定为 `<j2ll-home>/zig/zig` 或 `<j2ll-home>/zig/zig.exe`。

Resolution order：

1. 检查 `<j2ll-home>/zig/zig(.exe)` 是否存在。
2. 执行 `zig version`；只有输出等于 `0.15.2` 才可复用。
3. 如果 managed Zig 缺失或版本不匹配，先在 `<j2ll-home>` 查找当前 host 对应的 Zig `0.15.2` 压缩包。
4. 如果本地压缩包存在，直接解压，并将官方 archive 根目录内容规范化为 `<j2ll-home>/zig/zig(.exe)` + `<j2ll-home>/zig/lib` 布局。
5. 如果本地压缩包不存在，再从 `https://ziglang.org/download/0.15.2/` 下载对应压缩包，保存到 `<j2ll-home>`，再按同样规则解压/规范化。
6. 解压完成后再次执行 `zig version`，不等于 `0.15.2` 则 preflight error。

Expected archive names：

| Host | Archive |
| --- | --- |
| Windows x86_64 | `zig-x86_64-windows-0.15.2.zip` |
| Windows AArch64 | `zig-aarch64-windows-0.15.2.zip` |
| Linux x86_64 | `zig-x86_64-linux-0.15.2.tar.xz` |
| Linux AArch64 | `zig-aarch64-linux-0.15.2.tar.xz` |
| macOS x86_64 | `zig-x86_64-macos-0.15.2.tar.xz` |
| macOS AArch64 | `zig-aarch64-macos-0.15.2.tar.xz` |

Managed Zig rules：

- `zig/` 是 j2ll 管理目录；版本不匹配时可被重新安装。
- managed Zig 目录内必须直接包含 `zig` 或 `zig.exe` 可执行文件。
- 下载前必须优先使用 `<j2ll-home>` 下已存在的对应压缩包。
- 下载来源固定为 Zig 官方 download path，不使用 `latest`。
- local/downloaded archive 必须按内置官方 Zig `0.15.2` SHA-256 metadata 校验；checksum mismatch 是 native/toolchain failure，不能继续解压或 fallback 成成功。
- signature verification 当前是显式边界，report 使用 `signatureStatus=notVerifiedBoundary`。
- archive extraction 必须防 zip-slip / path traversal，不能写出 `<j2ll-home>/zig`。
- Zig compiles/links all buildable selected target dynamic libraries. Schema v1 records every selected target in preflight/report, and selected targets are required by default. A selected target that preflight cannot build is reported in `failedTargets` with `ZIG_TARGET_UNBUILDABLE`, includes required/optional state, Zig target triple, expected library path/name, failure kind, exact reason and build log tail, and makes the pipeline fail; optional/report-only target simulation belongs only in focused toolchain tests.
- Per-class `.ll`, Zig-managed `.o`, JNI wrapper C, runtime helper C and fallback blob carrier sources are all Zig toolchain inputs.
- j2ll generates one `build.zig` workspace per build. The Java side invokes managed `zig build` once for the selected target matrix; it must not issue ad-hoc per-target `zig cc`, host `cc`, `clang`, `llc` or platform linker commands.
- j2ll must not silently fall back to host `cc`, platform linker, external `clang` or external `llc` outside the managed `ZigToolchain` capability contract.
- If Zig cannot compile/link a required `.ll` / `.o` / C input for a selected target, preflight/build fails with a diagnostic that names the missing toolchain capability and the affected target. The stable reason for selected required targets that cannot be built in the current environment is `ZIG_TARGET_UNBUILDABLE`.

`signaturePolicy`

Signed input JAR handling. Required. Allowed values:

- `fail`: default/recommended v1 behavior. If input contains Java signature files, j2ll exits before rewriting because signatures would become invalid.
- `strip`: remove existing `META-INF/*.SF`, `META-INF/*.RSA`, `META-INF/*.DSA` and `META-INF/*.EC`, emit a warning, and record the action in `reports/packaging-report.json`.
- `resign`: remove old signatures and sign the output JAR using `signing`. Current implementation runs signing config/keystore/password/alias preflight before rewrite, then invokes the current JDK `jarsigner` on the generated output JAR. Preflight or signer failure records a precise diagnostic and does not keep a final JAR; success records `SIGNATURE_RESIGNED`.

`signing`

Signing config. Required field; may be `null` unless `signaturePolicy` is `resign`.

When `signaturePolicy` is `resign`, required fields are:

- `keystorePath`
- `storePasswordEnv`
- `keyAlias`
- `keyPasswordEnv`
- `tsaUrl`, nullable

`intermediates`

Controls intermediate artifact output. Required field. To use defaults, include the object with the documented fields and set each field to the desired default value.

Fields:

- `enabled`: write intermediate artifacts.
- `includeDebugDumps`: write stage debug dumps.
- `includePerClassIr`: write class-aligned SSA IR files.
- `includePerClassLlvm`: write class-aligned LLVM IR files.
- `includePerClassC`: write class-aligned C wrapper/runtime glue files where applicable.

`protection`

Controls SSA IR protection, LLVM module model protection and binary hardening. Required field. The recommended default config enables protection and enables all implemented protection passes.

Fields:

- `enabled`: master switch for all protection layers.
- `seed`: optional fixed seed. If `null`, j2ll derives a deterministic seed. Reports and final JAR metadata record only SHA-256 seed hashes, never the raw configured or derived seed.
- `ir`: SSA IR protection settings.
- `llvm`: LLVM module model protection settings.
- `binary`: binary visibility/strip settings.

Protection availability behavior:

- If config enables a known protection pass that has not been implemented yet, j2ll emits a warning and ignores that pass.
- If a protection pass is implemented but not applicable to a specific method, j2ll skips that pass for that method and emits a warning; the method is not marked `frontendSkipped` only because one protection pass is inapplicable.
- If a protection pass declares a hard requirement such as `classPath`, JDK metadata or target toolchain support and the requirement is missing, preflight emits a clear error and exits. The error must name the pass and tell the user to provide the missing input or disable that pass.

### Protection IR Fields

`protection.ir.enabled`

Master switch for SSA IR protection passes.

Pass fields:

- `controlFlowFlattening`: dispatcher-based CFG flattening.
- `fakeBranches`: opaque predicates and fake branches.
- `basicBlockSplitting`: split large basic blocks.
- `constantEncryption`: encode/decode numeric constants.
- `stringEncryption`: encrypt string literals and emit decode helpers.
- `methodInlining`: inline selected methods.
- `methodSplitting`: outline selected method fragments.
- `callIndirection`: route calls through tables/dispatchers/helpers.
- `methodTableHiding`: hide method table and dispatch mapping metadata.

Each pass has:

- `enabled`: enable this pass.

### Protection LLVM Fields

`protection.llvm.enabled`

Master switch for LLVM module model protection passes.

Pass fields:

- `nameObfuscation`: de-semanticize LLVM function/global names.
- `opaquePredicates`: insert LLVM-level opaque predicates.
- `blockLayoutPerturbation`: reorder LLVM basic block layout.
- `indirectCalls`: lower eligible direct calls to native-level indirection.
- `globalLayout`: perturb globals/string/data layout.
- `visibilityHardening`: normalize linkage and visibility before native build.

LLVM protection operates on `backend.llvm.model`. It must not mutate `.ll` text using string replacement.

Current schema v1 implements `indirectCalls` for same-class selected static/private direct LLVM calls by inserting hidden signature-group function-pointer tables named `j2ll_cit_<sha256>` into the LLVM module model and the Zig workspace `.ll` input. The protection report uses reason code `CALL_INDIRECTION_TABLE` when a table is emitted and `CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE` when table mode has no eligible direct call. A hidden dispatcher switch fallback named `j2ll_cid_<sha256>` remains available and reports `CALL_INDIRECTION_DISPATCHER`. Table, dispatcher and Java implementation symbols are internal/native hidden symbols and must not appear in `reports/symbol-audit.json` as dynamic exports.

### Protection Binary Fields

`protection.binary.enabled`

Master switch for binary hardening.

Fields:

- `hideInternalSymbols`: make internal Java method/native helper symbols hidden or internal.
- `strip`: strip unneeded symbols in release artifacts.
- `removePdb`: do not package Windows PDB files and remove accidental `.pdb` outputs.
- `symbolAudit`: inspect final dynamic libraries and fail if exported symbols exceed the allowlist.

## Output Workspace

Each run creates:

```text
<outputDirectory>/build_YYYY-MM-DD_HH-mm-ss/
```

Required top-level layout:

```text
build_YYYY-MM-DD_HH-mm-ss/
  config.resolved.json
  output/
    <input-jar-file-name>
  reports/
    artifact-audit.json
    diagnostics.json
    failure-report.json
    frontend-skip-report.json
    known-blockers.json
    lowering-report.json
    opcode-support-matrix.json
    packaging-report.json
    protection-report.json
    index.json
    release-readiness.json
    release-suite-summary.json
    summary.json
    summary.md
    support-matrix.json
    symbol-audit.json
  native/
    windows-x64/
    windows-arm64/
    linux-x64/
    linux-arm64/
    macos-x64/
    macos-arm64/
  intermediates/
    classes/
    runtime/
    dumps/
  logs/
```

`config.resolved.json`

Fully resolved config with defaults, absolute paths, a hash-only protection seed identity, resolved target list and normalized selectors. The raw protection seed is not written to this report.

`output/<input-jar-file-name>`

Final repacked JAR. This is the primary output artifact.

The final JAR also contains j2ll metadata entries written before signing/resigning:

- `META-INF/j2ll/build-info.json`: tool/schema version, config hash, selected targets, managed Zig version and protection seed hash.
- `META-INF/j2ll/native-libraries.json`: embedded native library target, JAR path and SHA-256.
- `META-INF/j2ll/reports-manifest.json`: report manifest hash, report names, `reportIndex=reports/index.json` and `reportHashSource=workspaceReportIndexSha256`.

These metadata entries must not contain sensitive plaintext or a raw protection seed.

`reports/diagnostics.json`

All diagnostics with stable ordering. Each entry includes a short user-facing `hint` when the reason code has a stable remediation path, for example selector grammar, missing `schemaVersion`, Zig target preflight, signed input policy or artifact-audit plaintext leak.

`reports/failure-report.json`

Written for failed config or pipeline runs. It summarizes error diagnostics with stable `primaryDiagnosticId`, `stage`, `reasonCode`, `message`, `hint`, affected selector/method/target fields where available, and `finalArtifactWritten=false`. It is a failure hygiene sidecar; successful runs may omit it.

`reports/artifact-audit.json`

Artifact audit v2.2 result. Successful pipeline runs audit the output JAR and embedded native resources for plaintext generated fallback `.class` entries, legacy output paths, native library resource placement under `embeddedLibraryDirectory`, embedded native SHA-256 consistency with `packaging-report.json`, final JAR metadata consistency with packaging target artifacts, hidden/protection/internal symbol export leaks (`j2ll_f_`, `j2ll_cit_`, `j2ll_cid_`, `Java_`), Windows PDB exclusion and sensitive-plaintext facts in generated C/LLVM/native workspace artifacts. The report includes `checkedSensitiveFacts`, `observedOnlySensitiveFacts` and `skippedSensitiveFacts`; each entry is hash-only and includes `literalHash`, `sourceMethod`, `passName`, `pathKind`, `gateMode`, `sourceSurface`, `reason` and `promotionReason`. `LLVM_NATIVE_PATH` connected surfaces, `TEMPLATE_JNI_PATH_STABLE_SURFACE` constructor/body helper string surfaces and StringConcat constant carrier stable generated-C surfaces are blocking when the literal is long enough to be a stable audit signal. Short/common literals that can naturally collide with report field names, JVM metadata or runtime support names are recorded as hash-only `observedOnly` with `PLAINTEXT_LITERAL_TOO_SHORT_FOR_BLOCKING_GATE`. Reflection/lambda/MethodHandle metadata facts remain `observedOnly`; complex fallback blob facts remain observed-only for plaintext literal gating but have blocking binary metadata/carrier checks. The checks array also records surface coverage for generated C, per-class LLVM `.ll`, `build.zig`, native library resources, output JAR entries, symbol audit output, packaging report paths, fallback blob binary metadata and final JAR metadata; skipped surfaces must include `surfaceNotGenerated`, `nonBlockingPathKind` or `unavailableOnTarget` style reasons. Failed runs write a no-final-artifact audit result so readiness reports do not confuse a missing final JAR with a successful artifact. Artifact audit is a finalization gate: if it fails after output packaging, j2ll must delete or avoid retaining the final JAR, write `reports/failure-report.json` with `stage=ARTIFACT_AUDIT`, `reasonCode=ARTIFACT_AUDIT_FAILED`, and leave readiness `finalArtifactWritten=false`.

`reports/frontend-skip-report.json`

Every requested method that became `frontendSkipped`, including selector, class, method, descriptor, skip stage, reason code and human-readable reason. `notApplicable` selector matches are reported in `reports/lowering-report.json`, not here.

`reports/lowering-report.json`

Requested lowering set, `lowered` methods, `halfLowered` methods, `frontendSkipped` methods, `notApplicable` selector matches, excluded methods and failures.

`reports/opcode-support-matrix.json`

Deterministic opcode/category/status/reason/test coverage matrix used by release readiness gates. Each row includes `testCoverage`, `coverageLevel` (`unit`, `integration`, `childJvmE2e`, or `releaseSuite`) and `evidenceCount`. It covers supported direct lowering, helper-backed opcodes, fallback opcodes and precise frontend skip boundaries such as legacy subroutines/finally shapes.

`reports/packaging-report.json`

Manifest/resource/signature handling, generated loader classes, native registration summary and output jar validation result.

`reports/protection-report.json`

Protection passes that ran, hash-only seed identity, per-method skipped pass reasons and fallback reasons. Reports may include root and per-pass `sensitivePlaintextFacts`; each fact records `literalHash`, `sourceMethod`, `passName`, `pathKind`, `gateMode`, `sourceSurface`, `reason`, `promotionReason` and `artifactSurfaces`, never the original plaintext. The pipeline may keep plaintext in memory long enough to feed artifact audit, but report JSON remains hash-only.

`reports/support-matrix.json`

Deterministic feature/status/reason/test coverage matrix for Java/JVM support tiers, helper/fallback boundaries, signing, managed Zig build and packaging behavior. Each row includes `testCoverage`, machine-readable `coverageLevel` and `evidenceCount`.

`reports/known-blockers.json`

Known release blockers that remain intentionally conservative. Each row has stable id, reason code, severity, target milestone, current behavior, report location and suggested future path. `severity` uses `beta-blocker`, `rc-blocker`, `future-blocker` or `non-goal`; `targetMilestone` uses values such as `beta`, `rc`, `post-rc` or `explicit-nongoal`. Explicit non-goals record JVM-hosted boundaries such as no standalone/native-image output and no native object model/GC/thread scheduler.

`reports/summary.json`

User-readable machine-parseable summary report written for build, dry-run and config-failure CLI workspaces. It aggregates final status, final artifact state, output JAR path, diagnostics counts/top errors, method status counts, native target status/resource/SHA summary, protection/audit counts, artifact-audit status, readiness status/top missing evidence and top blocker ids. It is derived from existing reports and does not include sensitive plaintext or raw protection seeds.

`reports/summary.md`

Diff-stable human summary derived from `reports/summary.json`. It lists final status, final artifact state, output JAR path, diagnostics counts, method status counts, native target buildable/unbuildable summary and gate status without copying raw protection seeds, sensitive plaintext or local workspace paths.

`reports/index.json`

Stable report manifest for the workspace. It lists every generated `.json` / `.md` report except itself, plus `config.resolved.json` and `intermediates/intermediates-manifest.json` when present. Each entry includes `path`, `reportVersion`, `sha256`, `requiredForReadiness`, `requiredForBeta`, `requiredForRc`, `producedOnFailure` and coarse `status`. Final JAR `META-INF/j2ll/reports-manifest.json` includes the expected report names, including `index.json` and `summary.md`, plus `reportIndex` / `reportHashSource`; the workspace index is the authoritative source for emitted report hashes, and readiness validates required report existence/hash plus final JAR report-manifest consistency.

`reports/release-readiness.json`

Release readiness gate result. The gate validates that required reports exist and that artifact audit, packaging, symbol audit, support matrix, opcode matrix and known blockers contain their contract fields. A failed gate is a report/preflight signal, not a standalone runtime mode. Schema v1 currently includes v3 readiness evidence fields:

- `missingEvidence`: machine-readable failed-evidence summary with `type`, `name`, `reasonCode`, `detail` and `reportPath`. Types include `missingReport`, `missingBlockerEvidence`, `missingSuiteCategory`, `artifactAuditNotPassed`, `metadataConsistencyMissing`, `blockingSensitivePlaintextLeak`, `determinismMissing`, `targetEvidenceIncomplete` and `failedCheck`.
- `suiteCoverageByBlocker`: one entry per known blocker with blocker id, reason code, report location, coverage state, evidence type (`releaseSuiteCase`, `weirdBytecodeSeed`, or `missing`), case name when applicable and expected status.
- `blockerEvidenceComplete`: true only when every known blocker has release suite or explicit seed evidence in strict suite mode.
- `targetEvidenceComplete`: true when every selected target artifact entry records required/buildable state, Zig triple, expected library path/name, reason, capability, SDK requirement, failure kind, build log tail and correct actual-artifact nullability.
- `finalArtifactWritten`: true only when the final output JAR exists. A failed required target must leave this false.
- `determinismEvidenceComplete`: true when strict suite summary includes stable case/report ordering and determinism evidence.
- `metadataConsistencyPassed`: true only when artifact audit reports final JAR metadata/schema/report-version/report-manifest/native-library consistency.
- `blockingSensitiveFactsPassed`: true only when blocking sensitive plaintext facts have no generated artifact or JAR plaintext leak.
- `targetPackagePlanComplete`: true only when selected target package planning evidence is complete.
- `betaProfilePassed`: true when strict suite mode uses `profile=beta` and has CLI artifact smoke, docs example validation and report-index evidence.
- `betaMissingEvidence`: short machine-readable beta evidence gaps.
- `cliArtifactSmokePassed`: true when beta suite evidence includes `java -jar j2ll.jar --help/--version` smoke coverage.
- `docsExamplesValidated`: true when beta suite evidence includes docs examples validation coverage.
- `strictModePassed`: true only when strict suite mode was requested and all checks passed.

`reports/release-suite-summary.json`

Strict readiness consumes release suite summaries by profile:

- `smoke`: narrow compiler/runtime sanity evidence.
- `standard`: regular helper/fallback/protection regression evidence.
- `beta`: user-facing usability evidence. Requires CLI jar smoke, docs examples validation, report index evidence, minimal LLVM native evidence and mixed helper/fallback evidence. `beta-blocker` rows must be covered by suite evidence or accepted workaround evidence; otherwise `betaProfilePassed=false`. Future or explicit non-goal blockers remain visible but do not block beta when they have evidence/future path.
- `rc`: release-candidate evidence. Requires all RC categories, blocker evidence, determinism, signing/packaging preservation, artifact audit failure evidence and required non-host target failure evidence.

Sample project docs live under `docs/samples/`, currently `basic-cli-app.md` and `reflection-service-app.md`. They include source snippets, config shape, commands, expected output and report highlights, and are tested so they do not drift away from `docs/examples/*.json`.

Release suite summary written by the deterministic test harness, not by ordinary CLI pipeline runs. Strict readiness mode requires this file for suite workspaces. It records `schemaVersion`, `reportVersion`, `suiteName`, `profile` (`smoke`, `standard`, `beta` or `rc`), `requiredCategories`, `missingCategories`, stable `cases` ordering, `aggregate` (`totalCases`, `successCases`, `expectedFailureCases`, `casesByCategory`, `casesByFeature`, `strictEvidenceComplete`, `determinismEvidenceComplete`), root `determinismEvidenceComplete`, each case `name`, `category`, `features`, expected support statuses, original/output child JVM exit/stdout/stderr when child JVM differential is applicable, collected produced report paths, diagnostics, protection setting, signature policy and whether pipeline success was expected. Expected config/toolchain/artifact failures may omit original/output child JVM runs, but must record `expectedFailure=true`, `expectedFailureStage`, `expectedFailureReasonCode`, `finalArtifactWritten=false`, a matching diagnostic and `failure-report.json`. Beta profile strict readiness requires CLI artifact smoke, docs example validation and report-index evidence; RC profile strict readiness requires `missingCategories` to be empty.

Strict readiness gate v6 treats `expectedSupportStatuses` and `expectedSupportEvidence` as release blocker coverage evidence. `beta-blocker` and `rc-blocker` known-blocker reasons must be covered either by a suite case expected status/diagnostic or by a documented weird-bytecode seed reason. `future-blocker` and explicit `non-goal` rows remain visible in coverage output but do not block RC strict readiness. Expected failure cases, such as invalid config, signed input rejected by `signaturePolicy: "fail"`, artifact audit failure or a required non-host target with `ZIG_TARGET_UNBUILDABLE`, must have `output: null`, `finalArtifactWritten=false`, `failure-report.json` and a matching diagnostic/stage/reason; successful cases must include output child JVM results, passed artifact audit and the required report set.

`reports/symbol-audit.json`

Exported symbol allowlist, actual exported symbols and audit result for each dynamic library.

### Report JSON Minimum Schemas

All report arrays must use stable ordering: class internal name, method name, descriptor, stage, then deterministic artifact id.

Every primary report JSON object writes `schemaVersion` and `reportVersion`. Unknown config fields are warnings; reports are append-compatible, but existing field wire names should not be renamed casually.

`reports/diagnostics.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "reportVersion": 1,
  "diagnostics": [
    {
      "severity": "warning",
      "code": "JVM_HELPER_FALLBACK",
      "stage": "LOWERING",
      "class": "pkg/Foo",
      "method": "run",
      "descriptor": "()V",
      "instructionOffset": 12,
      "artifactId": "pkg/Foo#run!()V",
      "message": "virtual call requires JVM helper fallback",
      "decision": "halfLowered"
    }
  ]
}
```

Required diagnostic fields:

- `severity`: `info`, `warning` or `error`.
- `code`: stable machine-readable diagnostic code.
- `stage`: one of the stage enum values in `docs/pipeline/08-diagnostics-validation-testing.md`.
- `message`: human-readable message.
- `decision`: nullable; when present, one of `lowered`, `halfLowered`, `frontendSkipped`, `notApplicable`, `failed`, `excluded` or `warning`.

Location fields are nullable only when the diagnostic is not tied to a method or instruction:

- `class`
- `method`
- `descriptor`
- `instructionOffset`
- `artifactId`

`reports/frontend-skip-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "entries": [
    {
      "selector": "pkg/Foo#bad!()V",
      "class": "pkg/Foo",
      "method": "bad",
      "descriptor": "()V",
      "status": "frontendSkipped",
      "stage": "LOWERING",
      "reasonCode": "UNSUPPORTED_OPCODE",
      "reason": "jsr/ret is not supported by the configured tier",
      "affectsCallers": true
    }
  ]
}
```

`reports/lowering-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "requestedMethods": [
    {
      "class": "pkg/Foo",
      "method": "run",
      "descriptor": "()V",
      "methodId": "run__8f3a21c0d4e5f607",
      "status": "halfLowered",
      "rewriteStrategy": "nativeOriginal",
      "accessFlags": ["public"],
      "compilerFlags": ["synthetic"],
      "nativeSymbol": "j2ll_pkg_Foo_run_8f3a21c0d4e5f607",
      "registrationOwner": "pkg/Foo",
      "nativeImplementationPath": "LLVM_NATIVE_PATH",
      "helperBackedSites": [
        {
          "helper": "j2ll_rt_string_builder_append_ref",
          "reasonCode": "HELPER_BACKED_LOWERING"
        },
        {
          "helper": "field:pkg/Foo#counter!I",
          "reasonCode": "FIELD_HELPER"
        },
        {
          "helper": "direct:pkg/Foo#callee!(I)I",
          "reasonCode": "DIRECT_LLVM_CALL"
        }
      ],
      "fallbackSites": [
        {
          "instructionOffset": 12,
          "target": "java/util/List#size!()I",
          "reasonCode": "UNKNOWN_INTERFACE_TARGET",
          "fallbackMode": "nativeEmbeddedClassBlob"
        }
      ]
    }
  ],
  "notApplicable": [
    {
      "selector": "pkg/Api#call!()V",
      "class": "pkg/Api",
      "method": "call",
      "descriptor": "()V",
      "reasonCode": "ABSTRACT_OR_NO_CODE"
    }
  ]
}
```

`accessFlags` records JVM access facts. `compilerFlags` records audit-oriented flags such as `bridge`, `synthetic`, `enumGenerated` and `recordGenerated`; these flags do not imply skip.
`nativeImplementationPath` records whether the registered native body is `LLVM_NATIVE_PATH`, `TEMPLATE_JNI_PATH`, or `null` when no executable native body was produced for that requested method.
`helperBackedSites` must include helper-backed metadata/reflection/JNI/Unsafe/MethodHandle/ConstantDynamic lowering sites when the operation is preserved by a runtime helper rather than direct native IR. It also records field/array/arraycopy/allocation/String/StringBuilder/JDK/div-rem/JVM-numeric/monitor/exception/call/stub decisions: `FIELD_HELPER`, `ARRAY_HELPER`, `ARRAYCOPY_HELPER`, `ALLOCATION_HELPER`, `STRING_HELPER`, `STRING_BUILDER_HELPER`, `JDK_INTRINSIC_HELPER`, `JDK_COLLECTION_HELPER`, `THROWABLE_HELPER`, `THREAD_HELPER`, `WAIT_NOTIFY_FALLBACK`, `JVM_NUMERIC_HELPER`, `DIV_REM_EXCEPTION_HELPER`, `MONITOR_HELPER`, `SYNCHRONIZED_METHOD_HELPER`, `EXCEPTION_HELPER`, `REFLECTION_HELPER`, `REFLECTION_FIELD_HELPER`, `REFLECTION_METHOD_HELPER`, `REFLECTION_CONSTRUCTOR_HELPER`, `REFLECTION_ACCESSIBLE_HELPER`, `UNSAFE_HELPER`, `DIRECT_LLVM_CALL`, `JVM_CALL_HELPER`, `DISPATCH_HELPER`, `DEFAULT_INTERFACE_DISPATCH_HELPER`, `DEFAULT_INTERFACE_DISPATCH_FALLBACK`, `UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT`, `UNSUPPORTED_DEFAULT_INTERFACE_SUPER`, `DEFERRED_DISPATCH_HELPER`, `CONSTRUCTOR_BODY_HELPER`, `CLASS_INITIALIZER_BODY_HELPER`, `JNI_ABI_REGISTER_NATIVES` and `RUNTIME_METADATA_HELPER`. Current static reflection helper coverage includes no-arg, reference, primitive and array constant-parameter method/constructor descriptors, typed field accessors `getInt/setInt/getBoolean/setBoolean/getLong/setLong/getDouble/setDouble`, reference `Field.get/set`, and a bounded `setAccessible(true)` helper for statically resolved Method/Constructor/Field objects. Dynamic reflection strings, dynamic parameter arrays, and scan-style reflection (`getDeclaredMethods/getMethods/getDeclaredFields/getFields/getDeclaredConstructors/getConstructors`) ordinary calls use JVM dispatch bridge when the descriptor fits the supported JNI bridge matrix, with `DEFERRED_DISPATCH_HELPER` / `JVM_CALL_HELPER` evidence rather than native reflection metadata interpretation. MethodHandle common adapter chains use JVM `MethodHandle.invokeWithArguments` bridge; this avoids copying signature-polymorphic `invokeExact` bytecode into fallback helper classes and is not a generic native MethodHandle interpreter. Unsupported Unsafe raw memory APIs, unsupported ConstantDynamic bootstraps, reflection shapes beyond the bridge matrix, unsupported altMetafactory/lambda shapes, and remaining finally holes must appear in diagnostics/fallback sites with stable reason codes such as `REFLECTION_DYNAMIC_FALLBACK`, `REFLECTION_UNSUPPORTED_SCAN`, `UNSAFE_RAW_MEMORY_FALLBACK`, `ALT_METAFACTORY_FALLBACK`, `UNSUPPORTED_NESTED_FINALLY` or `UNSUPPORTED_EXCEPTION_STATE_MERGE` rather than being silently skipped. `I.super.m()` default-interface super invokespecial is currently `frontendSkipped` with `UNSUPPORTED_DEFAULT_INTERFACE_SUPER` because copying it into a helper class violates direct-superinterface verification. `JDK_COLLECTION_HELPER` records ArrayList/HashMap/Arrays/Collections/Optional/String.format sites whose JVM library semantics are intentionally not lowered through native object layout; `JDK_HELPER_FALLBACK` records the corresponding explicit bytecode-preserving `nativeEmbeddedClassBlob` fallback, including narrow `java.util.Arrays.copyOf/equals/fill/asList`, `Collections.emptyList/singletonList`, `Optional` and `String.format` JVM library semantics. `THROWABLE_HELPER_FALLBACK` records Throwable message/cause/constructor semantics that remain JVM-owned, `THREAD_HELPER_FALLBACK` records Thread constructor/start/join semantics that remain JVM-scheduler-owned, and `WAIT_NOTIFY_FALLBACK` records wait/notify monitor-queue semantics that are not implemented in native code. In schema v1, `Unsafe.objectFieldOffset`/`staticFieldOffset` reports describe deterministic metadata tokens, not native object layout offsets.

Runtime metadata dumps are stable sidecars when enabled by intermediates/debug dumps. They may include a `reflectionReachability` section with resolved class/method/field targets and reflection fallback sites. The dump is an observability artifact; lowering status remains governed by `reports/lowering-report.json`.

`reports/packaging-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "outputJar": "output/input.jar",
  "manifestPolicy": "preserved",
  "signaturePolicy": "fail",
  "preservationSummary": {
    "manifestPreserved": true,
    "serviceEntriesPreserved": 1,
    "moduleInfoPreserved": true,
    "multiRelease": true,
    "versionedEntriesPreserved": 1,
    "versionedClassPolicy": "baseClassesOnlyPreserveVersionedEntries"
  },
  "signatureAction": {
    "inputSigned": false,
    "policy": "fail",
    "action": "none",
    "signatureEntries": [],
    "warning": null,
    "error": null
  },
  "generatedLoaders": ["j2ll/generated/abc123/NativeLoader"],
  "rewrittenClasses": [
    {
      "class": "pkg/Foo",
      "methods": [
        {
          "method": "run",
          "descriptor": "()V",
          "rewriteStrategy": "nativeOriginal",
          "registrationOwner": "pkg/Foo"
        }
      ]
    }
  ],
  "embeddedLibraries": [
    {
      "target": "linux-x64",
      "jarPath": "native0/x64-linux.so",
      "sha256": "..."
    }
  ],
  "zigToolchain": {
    "managed": true,
    "version": "0.15.2",
    "executable": "<j2ll-home>/zig/zig",
    "buildZig": "native/zig-workspace/build.zig",
    "manifest": "native/zig-workspace/j2ll-build-manifest.json",
    "verificationPolicy": "sha256Required:signatureNotVerifiedBoundary",
    "bootstrapEvents": [
      {
        "code": "ARCHIVE_CHECKSUM_VERIFIED",
        "archiveName": "zig-aarch64-macos-0.15.2.tar.xz",
        "archiveSha256": "3cc2bab367e185cdfb27501c4b30b1b0653c28d9f73df8dc91488e66ece5fa6b",
        "checksumStatus": "verified",
        "signatureStatus": "notVerifiedBoundary",
        "source": "localArchive"
      }
    ],
    "buildCommand": ["<j2ll-home>/zig/zig", "build", "--prefix", "..."],
    "selectedTargets": ["linux-x64", "macos-arm64"],
    "requiredTargets": ["linux-x64", "macos-arm64"],
    "buildableTargets": ["macos-arm64"],
    "skippedTargets": [],
    "failedTargets": [
      {
        "target": "linux-x64",
        "zigTarget": "x86_64-linux",
        "output": "native/linux-x64/x64-linux.so",
        "status": "failed",
        "currentHost": false,
        "buildable": false,
        "reasonCode": "ZIG_TARGET_UNBUILDABLE",
        "reason": "selected required target linux-x64 is not buildable by the current managed Zig workspace preflight",
        "requiredCapability": "managedZig0.15.2BuildZigSharedLibrary",
        "platformSdkRequirement": "Zig Linux libc/linker support for selected target"
      }
    ]
  },
  "fallbackBlobs": [
    {
      "originalMethodId": "run__8f3a21c0d4e5f607",
      "originalMethodKey": "pkg/Foo#run!()V",
      "helperClassName": "pkg/J2llFallback$run__8f3a21c0d4e5f607",
      "fallbackInvokeDescriptor": "()V",
      "fallbackReasonCode": "JVM_HELPER_FALLBACK",
      "sha256": "...",
      "originalSha256": "...",
      "encodedSha256": "...",
      "encodingVersion": "fallbackBlobEncodingV1",
      "originalSize": 1234,
      "encodedSize": 900,
      "compressionAlgorithm": "j2ll-rle-byte-pairs-v1",
      "encryptionAlgorithm": "xor-sha256-key-stream-v1",
      "requiredJavaVersion": "8",
      "storageTarget": "nativeEmbeddedClassBlob",
      "definitionMechanism": "HiddenClass",
      "definitionMechanismReasonCode": "FALLBACK_HIDDEN_CLASS",
      "hiddenClassApiAvailable": true,
      "ownerLookupSupported": true,
      "definitionMechanismReason": "owner-private Lookup can define hidden fallback helper class",
      "cacheReasonCode": "FALLBACK_CACHE_REUSE",
      "classloaderReusePolicy": "lazyPerClassLoaderReuse",
      "cacheScope": "process",
      "cacheKey": "fallbackId+definingClassLoaderIdentity",
      "cacheLifetime": "processLifetime",
      "globalReferencePolicy": "globalRefPerFallbackClassAndClassLoader"
    }
  ]
}
```

`reports/protection-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "reportVersion": 1,
  "seedHash": "sha256-of-derived-or-configured-seed",
  "passes": [
    {
      "passName": "STRING_ENCRYPTION",
      "layer": "IR",
      "status": "RAN",
      "reasonCode": "OK",
      "affectedMethods": [
        "pkg/Foo#run!()V"
      ],
      "affectedSymbols": [],
      "seedHash": "sha256-of-pass-seed"
    }
  ]
}
```

Pass `status` values are `RAN`, `SKIPPED` and `FAILED`. Every pass result records a stable `reasonCode`; examples include `OK`, `FLOAT_CONSTANT_ENCRYPTION`, `DOUBLE_CONSTANT_ENCRYPTION`, `CONTROL_FLOW_FLATTENING`, `CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE`, `CALL_INDIRECTION_TABLE`, `CALL_INDIRECTION_DISPATCHER`, `CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE`, `PROTECTION_PASS_DISABLED`, `NO_STRING_CONSTANT_CARRIER`, `NO_PRIMITIVE_CONSTANTS`, `PROTECTION_CFG_SHAPE_NOT_SUPPORTED`, `PROTECTION_STUB_BACKED_METHOD`, `PROTECTION_MONITOR_SENSITIVE_SKIP` and `CALL_INDIRECTION_UNSUPPORTED_SHAPE`. Disabled pass and per-method inapplicability both use `SKIPPED`. Configured but unimplemented pass warnings also appear in diagnostics and must not silently change a method lowering status.

`reports/symbol-audit.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "libraries": [
    {
      "target": "linux-x64",
      "path": "native/linux-x64/x64-linux.so",
      "allowedExports": ["JNI_OnLoad", "j2ll_register"],
      "actualExports": ["JNI_OnLoad", "j2ll_register"],
      "unexpectedExports": [],
      "missingExports": [],
      "status": "passed"
    }
  ]
}
```

## Output JAR Layout

The final output jar must contain:

```text
<original resources>
<rewritten classes>
<embeddedLibraryDirectory>/
  x64-windows.dll
  arm64-windows.dll
  x64-linux.so
  arm64-linux.so
  x64-macos.dylib
  arm64-macos.dylib
```

Only selected targets appear. For example, if `linuxX64` and `macosArm64` are true, only these files are required:

```text
<embeddedLibraryDirectory>/x64-linux.so
<embeddedLibraryDirectory>/arm64-macos.dylib
```

The output jar must also contain generated loader/registration classes needed to load the embedded native libraries and bind native methods. j2ll uses generated loader classes plus `RegisterNatives`; Java method implementation functions remain internal/hidden and are not exported as JNI method-name symbols.

### Method Rewrite Strategies

Packaging rewrites methods according to their JVM method kind. The strategy is recorded in `reports/lowering-report.json` and `reports/packaging-report.json`.

`nativeOriginal`

普通 class method with Code, excluding `<init>` and `<clinit>`.

- The original method keeps its original name, descriptor and user-visible access flags.
- The Code attribute is removed and the method is marked `ACC_NATIVE`.
- The generated native implementation is bound with `RegisterNatives`.
- The native implementation symbol is internal/hidden; it is not exported as a JNI name-mangled method symbol.

`constructorStub`

Java `<init>` cannot become an ordinary native method. j2ll must keep a legal constructor stub.

- The original `<init>` remains a non-native constructor with Code.
- The stub preserves the verifier-required constructor delegation path to `this(...)` or `super(...)`.
- After the object is initialized, the stub calls a private generated native body helper, for example `__j2ll_init_body$<method-id>(this, originalArgs...)`.
- If the pre-initialization prefix contains only verifier-required stack/local setup, the method may still be reported as `lowered`.
- If meaningful pre-initialization user bytecode must remain in the Java stub, the method is reported as `halfLowered` with a constructor-stub diagnostic.
- If the constructor shape cannot be split while preserving verifier semantics, it is `frontendSkipped` or `failed` depending on whether the original bytecode can remain runnable.

`classInitializerStub`

Java `<clinit>` is invoked implicitly by the JVM and is not a normal native method target. j2ll must keep or create a legal class initializer stub.

- If a class has lowered methods and no `<clinit>`, j2ll may generate one.
- The stub first calls the generated loader to load the native library and register owner-class native methods.
- If the original `<clinit>` has Code, its body is lowered into a private generated static native helper, for example `__j2ll_clinit_body$<method-id>()`.
- The stub calls the native helper after loader initialization.
- The loader and registration path must handle recursive class initialization and classloader concurrency.

`interfaceMethodStub`

Interface methods cannot be marked native, but Java 8+ interface default, static and private methods may have Code.

- The original interface method remains a legal Java method with Code.
- The stub calls the generated loader and then a generated class helper that owns the actual native method.
- For default interface methods, the helper receives `this` explicitly.
- Abstract interface methods, annotation elements and interface declarations without Code are recorded as `notApplicable`.

`notApplicable`

No rewrite is attempted for methods without lowerable method bodies:

- abstract methods.
- already-native methods.
- interface methods without Code.
- annotation elements without Code.

These methods are recorded for audit when matched by selectors. They do not fail the build and do not count as `frontendSkipped`.

### Repackaging Rules

The output jar must remain runnable after repackaging.

Manifest rules:

- Preserve `META-INF/MANIFEST.MF` main attributes unless j2ll explicitly owns a generated attribute.
- Preserve `Main-Class`, `Premain-Class`, `Agent-Class`, `Launcher-Agent-Class`, `Automatic-Module-Name`, `Multi-Release` and existing package section attributes.
- If j2ll adds generated metadata, use a reserved prefix such as `J2LL-`.
- Do not remove or rewrite `Class-Path` unless a later packaging policy explicitly owns dependency relocation.

Resource rules:

- Preserve non-class resources byte-for-byte unless a documented policy excludes or regenerates them.
- Preserve `META-INF/services/*` entries. If a future runtime feature needs service injection, it must merge service files without dropping existing provider lines.
- Preserve `module-info.class` unless a future module-aware rewrite phase explicitly owns module metadata updates.
- Preserve `META-INF/versions/**` entries. Multi-release class lowering is a separate policy decision; until implemented, selected lowering should target base classes only and report versioned classes as unsupported or skipped.

Signature rules:

- Existing Java signatures are invalidated when classes/resources are rewritten.
- `signaturePolicy: "fail"` rejects signed input before rewrite.
- `signaturePolicy: "strip"` removes old signature files and produces an unsigned runnable jar.
- `signaturePolicy: "resign"` removes old signature files and signs the output jar with the configured key.
- Every signature decision must be recorded in `reports/packaging-report.json`.
- `reports/packaging-report.json` also records `zigToolchain.targetArtifacts`, including selected/required target, current-host/buildable state, OS/arch classifier, library extension, Zig target triple, expected artifact path/name/resource path, loader extraction path policy, symbol visibility policy, actual artifact SHA-256 for built current-host targets, exported symbols, required capability, platform SDK requirement, failure kind, build log tail and Windows PDB exclusion policy.
- `reports/support-matrix.json` is a stable release-readiness artifact listing feature, support status (`LLVM_NATIVE_PATH`, `HELPER_BACKED`, `FALLBACK`, `FRONTEND_SKIPPED`, `NOT_APPLICABLE`), reason code and test coverage pointer.
- `reports/opcode-support-matrix.json` is the matching opcode-level release-readiness artifact listing opcode bucket, category, status, reason code and test coverage pointer.
- `reports/known-blockers.json` tracks remaining conservative boundaries with stable blocker id, reason code, severity, target milestone, report location and suggested future path.
- `reports/release-readiness.json` records the gate checks over required reports and their required top-level fields plus readiness fields `suiteCoverageByBlocker`, `blockerEvidenceComplete`, `targetEvidenceComplete`, `finalArtifactWritten`, `determinismEvidenceComplete`, `metadataConsistencyPassed`, `blockingSensitiveFactsPassed`, `targetPackagePlanComplete` and `strictModePassed`.
- `reports/release-suite-summary.json` is emitted by release suite tests and is required only by strict suite readiness mode. It records suite/case metadata, expected support statuses, expected support evidence with report locations, child JVM differential results and collected report paths. In strict v3, known blocker reasons must be covered by suite expected statuses/diagnostics or by weird-bytecode seed coverage, and expected failure cases must not produce output runs.

## Runtime, World, Loader, And Signature Policy

本节定义 runtime helper、JVM fallback、world model 和 native registration 的正式契约。

### Runtime Helper Fallback

Runtime helper 是随 native library 一起编译进去的 j2ll 小运行时。它不代表“放弃 native lowering”，而是让已经 lowered 的 native code 能正确执行 JVM 语义。

典型 runtime helper：

- null check、array bounds check、checkcast、instanceof。
- object/array allocation、class initialization、static field access guard。
- tokenized field get/put helper, allocation helper, String helper and helper-backed call dispatch. Current dispatch helper subset uses JNI `GetObjectClass` / `GetMethodID` / `Call<Type>Method` for no-arg int, int-arg int, reference return and single-reference-argument/reference-return virtual/interface calls; it is not a native vtable/object-layout mechanism.
- exception create/throw/catch bridge。
- monitor enter/exit 和 synchronized 相关状态维护。
- string/constant decrypt helper、protection dispatch helper、method table helper。
- JNI local/global reference lifetime helper。

它的特点：

- 调用方仍然是 native-lowered method。
- helper 可以用 LLVM/C 实现，也可以通过 JNI 调 JVM API。
- Java reference values in helper ABI are JVM objects/JNI references. Helpers must allocate Java-visible objects through JVM/JNI APIs, not native heap or native stack storage. Current allocation helpers use class identity tokens and JNI APIs such as `AllocObject`, `NewIntArray` and `NewObjectArray`; token metadata is sidecar/report data, not a native object layout.
- helper ABI 必须由 backend declaration、runtime stub generator 和 tests 共同约束。
- lowering report 的 `helperBackedSites` 记录这些 helper-backed lowered call/operation，用来和 `halfLowered` 的 JVM fallback sites 区分。

### JVM Helper Fallback

JVM helper fallback 是 native-lowered method 在某个 operation 或 call site 上无法安全静态 lowering 时，显式调回 JVM 执行原 bytecode 或外部 Java method。

典型场景：

- unresolved virtual/interface call。
- JDK/library method 暂未 native lowering，例如 ArrayList/HashMap narrow collection policy 当前以 `JDK_COLLECTION_HELPER` 标注 call site，并以 `JDK_HELPER_FALLBACK` 回到 bytecode-preserving fallback。
- reflection、dynamic class loading 或 classpath 不完整导致 call target 不确定。
- 某个 skipped method 被 lowered method 调用。

它的特点：

- 方法结果记录为 `halfLowered`，不是 `failed`。
- 每个 `halfLowered` method 必须产生 warning diagnostic，reason code 建议使用 `JVM_HELPER_FALLBACK`。
- lowering report 必须记录 fallback call sites、fallback target 和 fallback reason。
- 需要原 bytecode 或可调用 Java target 通过 `fallbackMode` 指定的方式可达。
- 性能较差，保护强度较弱，但语义更稳。
- 如果 fallback 需要原 method body，packaging 必须生成 fallback bytecode target，并按 `fallbackMode` 存储它，同时在 sidecar/report 中记录它和原 method 的映射。schema v1 的 ordinary method body fallback 使用同 owner package helper class 的 static synthetic `invoke` 方法；instance original method 的 helper descriptor 会把 owner instance 作为第一个参数。packaging report 必须记录 `fallbackInvokeDescriptor` 和 `fallbackReasonCode`，用于把 encoded helper ABI 与 lowering fallback reason 关联起来。
- JVM helper fallback 不导致构建失败。只有 output jar 无法保持可运行语义时，才允许把该 method 转为 `frontendSkipped` 或 `failed`。

`nativeEmbeddedClassBlob` 要求：

- fallback class bytes 不以明文 `.class` entry 形式写入 output JAR。
- fallback class bytes 被压缩、加密或至少不可直接作为 Java class resource 读取，并嵌入每个 selected target dynamic library。
- native library 包含 fallback blob manifest，记录 original method id、fallback helper class name、fallback invoke descriptor、fallback reason code、original SHA-256、encoded SHA-256、encoding version、加密/压缩算法和 required Java version。
- decoder 必须在分配 decoded class buffer 前校验 encoded SHA-256 和 compressed payload capacity；wrong fallback id/key、corrupted encoded payload、truncated RLE payload 或 hash mismatch 必须抛出清晰错误，不能导致 unbounded allocation / OOM。
- runtime helper 按 classloader 懒加载 fallback helper；同一 classloader 内重复调用必须复用已定义 helper。schema v1 cache policy 是 process-lifetime global reference cache，key 为 fallback id + defining classloader identity；当前没有 unload hook。
- helper definition 可以使用 JNI `DefineClass`、`MethodHandles.Lookup#defineHiddenClass` 或后续等价机制，具体机制必须记录在 packaging report。
- packaging report 必须记录 definition capability：`definitionMechanismReasonCode` 使用 `FALLBACK_HIDDEN_CLASS`、`FALLBACK_DEFINE_CLASS`、`FALLBACK_HIDDEN_CLASS_UNAVAILABLE` 或 `FALLBACK_HIDDEN_CLASS_UNSUPPORTED_ACCESS`；cache policy 使用 `FALLBACK_CACHE_REUSE` / `FALLBACK_CACHE_ISOLATED` 等稳定 reason code，并记录 `cacheScope`、`cacheKey`、`cacheLifetime`、`globalReferencePolicy`、`unloadAware=false` 和后续 unload-aware cache lifecycle `futurePath`。
- 如果当前目标 JDK 不支持所选 helper definition 机制，preflight 必须报错或选择已实现的兼容机制；不能退回明文 generated class。

### World Model

World model 是分析阶段对“程序类世界是否完整”的假设。它会影响 CHA/RTA、devirtualization、call indirection、method table hiding 等能力。

常见模型：

- `CLOSED_WORLD`：历史 wire name，表示输入 JAR、`classPath` 和 JDK metadata 覆盖分析需要的 JVM classes。可以做更激进 devirtualization 和 method table rewriting，但输出仍是 JVM-hosted JAR。
- `PARTIAL_WORLD`：应用 class 大体已知，但外部库或运行时可能不完整。分析必须对 external type 保守。
- `JDK_EXTERNAL_WORLD`：应用 class 可分析，JDK class 主要作为外部 runtime/library 处理。
- `UNKNOWN_DYNAMIC_WORLD`：允许 reflection、custom classloader、runtime generated class 改变类型世界。只能做非常保守的 dispatch 优化。

`worldModel` 是 required config field，推荐值为 `PARTIAL_WORLD`。如果某个 protection/analysis pass 需要更强 world model，但 config、`classPath` 或 JDK metadata 不能满足，应 preflight error 并提示关闭该 pass 或补全输入。

### Loader And Native Registration

loader/native registration 需要解决三件事：

- 从 output jar 中按 OS/arch 选择并加载对应 dynamic library。
- 确保 lowered Java methods 在第一次调用前绑定到 native implementation。
- 在 binary hardening 下只导出必要 ABI，隐藏 Java method internal LLVM functions。

正式方案：

- 使用 generated loader + `RegisterNatives`。
- generated loader internal name uses `j2ll/generated/<artifact-id>/NativeLoader`, where `artifact-id` is a filesystem/class-name safe token derived from the resolved config and input jar hash.
- Rewritten owner classes call the generated loader from `<clinit>` or from a generated method stub before the first native helper call. If an existing `<clinit>` exists, loader initialization is prepended before lowered method use.
- Dynamic libraries are extracted from jar resources to a per-classloader, content-addressed temp/cache path under `java.io.tmpdir`.
- Extracted libraries must be verified against SHA-256 metadata before `System.load`.
- Native registration tables are grouped per owner class.
- Interface method native helpers are registered against generated helper classes, not against the interface method itself.
- `<init>` and `<clinit>` body helpers are registered as generated private static native helper methods on the owner class, not as native constructors or native class initializers.
- Loader state is per classloader and thread-safe. It must avoid duplicate extraction/load/registration and must handle concurrent first use.
- Extraction paths must not be user-controlled relative paths; temp files should use restrictive permissions where the platform supports them.
- Only loader/bootstrap JNI wrapper symbols and optional `JNI_OnLoad` are exported. Java method implementation functions, dispatchers and protection tables stay internal/hidden.
- Loader, extraction and `RegisterNatives` failures throw `UnsatisfiedLinkError` with a clear message.

## Native Lowering Guarantee

For every method reported as `lowered`:

- The class bytecode in the output jar is rewritten to call native code.
- The corresponding native implementation exists in every selected target dynamic library.
- The native registration plan includes that method.
- The lowering report records the method as lowered.

For every method reported as `halfLowered`:

- The class bytecode in the output jar is rewritten to call native code.
- The corresponding native implementation exists in every selected target dynamic library.
- The native registration plan includes that method.
- The native implementation may call JVM helper fallback for recorded operation/call sites.
- The fallback plan stores any generated fallback bytecode target according to `fallbackMode`; schema version 1 stores it in native embedded fallback blobs, not as plain JAR classes.
- The lowering report records the method as `halfLowered` and diagnostics include a warning.

For every method reported as `frontendSkipped`:

- The original bytecode remains runnable in the output jar.
- The lowering report records the skip reason and stage.
- Protection pass inapplicability alone must not skip the method; it skips only that protection pass and emits a warning.

For every method reported as `notApplicable`:

- No native lowering or Java bytecode rewrite is attempted.
- The method is abstract, already native, an interface declaration without Code, an annotation element without Code, or another method kind with no lowerable body.
- The selector/report sidecar records why it did not enter the requested lowering set.

If any selected target fails to produce a dynamic library, the build fails.

## Intermediate Artifacts

Intermediate artifacts should preserve a one-to-one relationship with original JAR classes while remaining safe on case-insensitive and platform-specific filesystems. A class from:

```text
com/example/Foo.class
```

maps to:

```text
intermediates/classes/com/example/Foo__<class-hash-prefix>/
```

A nested class from:

```text
com/example/Foo$Bar.class
```

maps to:

```text
intermediates/classes/com/example/Foo$Bar__<class-hash-prefix>/
```

Class-aligned layout:

```text
intermediates/classes/<safe-internal-class-name>__<class-hash-prefix>/
  class-index.json
  parsed-class.json
  method-index.json
  hierarchy.json
  call-sites.json
  cfg/
    <method-id>.cfg.json
    <method-id>.cfg.txt
  ir/
    raw.ssa.ir
    optimized.ssa.ir
    protected.ssa.ir
  llvm/
    class.ll
    protected.class.ll
    class.o
  c/
    class.c
  reports/
    lowering.json
    protection.json
```

Rules:

- IR, LLVM IR, Zig-produced object files and class-specific C files should be emitted per original class.
- `intermediates/intermediates-manifest.json` records `schemaVersion`, `reportVersion`, the five `intermediates` config switches, class/method artifact ids and every emitted intermediate file with relative path, kind and SHA-256. The manifest excludes itself from its file list so repeated writes remain stable.
- `includeDebugDumps`, `includePerClassIr`, `includePerClassLlvm` and `includePerClassC` control whether CFG/runtime debug dumps, SSA IR, LLVM IR and class C wrapper files are written. Class/method indexes and per-class report stubs may still be written when `intermediates.enabled=true`.
- `<class-hash-prefix>` is the first 16 hex characters of SHA-256 over the original internal class name. If two class artifact directories collide, extend both prefixes to 24 hex characters, then 32, and continue in 8-hex increments.
- `<safe-internal-class-name>` preserves `/` as path separators and escapes each segment for filesystem safety.
- Segment escaping keeps ASCII letters, digits, `_`, `$`, `-` and `.`. Any other UTF-16 code unit is escaped as `_uXXXX_`.
- Windows reserved names, empty segments and segments ending in a space or dot must be prefixed or escaped by the artifact path planner.
- `class-index.json` is mandatory and records original internal class name, full SHA-256, chosen directory, source JAR entry and any escaping/collision extension.
- Do not emit one monolithic program IR/LLVM/C file first and then split it as the primary artifact model.
- Do not emit shard LLVM/C artifacts in the documented intermediate contract. If a future toolchain optimization needs temporary compiler units, those files must stay in toolchain temp space and must not replace per-class artifacts.
- `.o` files are build artifacts managed by Zig from class-aligned `.ll` / C inputs or accepted as explicit Zig toolchain inputs. They are not a replacement for the documented per-class `.ll` contract.
- `method-id` must be deterministic and collision-resistant for overloaded methods. Required shape:

```text
<safe-method-name>__<sha256-prefix>
```

Example:

```text
doWork__8f3a21c0d4e5f607.cfg.json
```

`sha256-prefix` is the first 16 hex characters of SHA-256 over:

```text
<internal-class-name>#<method-name>!<method-descriptor>
```

If two method ids collide inside the same class artifact directory, extend both colliding prefixes to 24 hex characters, then 32, and continue in 8-hex increments up to the full SHA-256 if needed.

`safe-method-name` rules:

- Java `<init>` becomes `_init_`.
- Java `<clinit>` becomes `_clinit_`.
- ASCII letters, digits, `_` and `$` are kept.
- Any other UTF-16 code unit is escaped as `_uXXXX_`.
- Empty names are invalid.

`method-index.json` is the mandatory sidecar JSON and must preserve:

- original internal class name
- original method name
- original method descriptor
- full SHA-256
- chosen `method-id`
- whether the method status is `lowered`, `halfLowered`, `frontendSkipped`, `notApplicable`, `excluded` or `failed`
- any display-safe escaping used in `safe-method-name`

## Runtime And Shared Artifacts

Runtime helper artifacts that are not tied to a single class go under:

```text
intermediates/runtime/
  runtime-helpers.c
  runtime-helpers.h
  helper-catalog.json
  fallback-blob-manifest.json
```

`fallback-blob-manifest.json` records generated fallback helper classes, owning methods, SHA-256 values, storage target, definition mechanism, definition capability reason and classloader cache lifecycle policy. It must not contain decrypted class bytes.

## Dynamic Library Output

Native libraries are written to workspace `native/` and embedded into the final jar.

Workspace paths:

```text
native/windows-x64/x64-windows.dll
native/windows-arm64/arm64-windows.dll
native/linux-x64/x64-linux.so
native/linux-arm64/arm64-linux.so
native/macos-x64/x64-macos.dylib
native/macos-arm64/arm64-macos.dylib
```

JAR paths:

```text
<embeddedLibraryDirectory>/x64-windows.dll
<embeddedLibraryDirectory>/arm64-windows.dll
<embeddedLibraryDirectory>/x64-linux.so
<embeddedLibraryDirectory>/arm64-linux.so
<embeddedLibraryDirectory>/x64-macos.dylib
<embeddedLibraryDirectory>/arm64-macos.dylib
```

Binary hardening rules:

- Export only JNI / C ABI wrapper symbols required by the loader/registration plan.
- Java method implementation functions are internal LLVM functions or hidden symbols.
- Internal helpers, dispatchers, method tables and protection tables are hidden unless explicitly required by runtime ABI.
- Windows release output must not package `.pdb` files.
- `reports/symbol-audit.json` must record the allowlist and actual exported symbols.

## Failure Outputs

On failure, the workspace remains for debugging. Expected files:

```text
config.resolved.json
reports/artifact-audit.json
reports/diagnostics.json
reports/failure-report.json
reports/frontend-skip-report.json
reports/known-blockers.json
reports/lowering-report.json
reports/opcode-support-matrix.json
reports/packaging-report.json
reports/protection-report.json
reports/release-readiness.json
reports/release-suite-summary.json
reports/support-matrix.json
reports/symbol-audit.json
logs/
intermediates/
```

`output/<input-jar-file-name>` is only written when the build succeeds.
