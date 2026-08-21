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

配置选中的 native lowering 范围必须全部有明确结果。对 selector 选中且带 Code 的 method，最终 method status 只有两种：

- `nativeLowered`：原 Java method body 已由经过验证的 native implementation 取代。
- `skipped`：该 method 没有进入 native registration，原 Java bytecode 原样保留。

`excluded` 只描述 selector/blacklist 之外的方法，不是 selected method 的 lowering status。parse、validation、toolchain、packaging 或 audit failure 是 build-level failure，也不是 method status。schema v1 不提供、也不计划新增 `requiredNative` 配置；是否接受本次构建中的 skipped methods 由下述 build-time confirmation gate 决定。

定义：

- `whiteList` 为空：所有未被 `blackList` 排除的可处理 class/method 都进入 requested lowering set。
- `whiteList` 非空：`whiteList` 命中的 class/method 先做 method eligibility 判定；有可改写方法体的 method 进入 requested lowering set。
- `blackList` 总是从 requested lowering set 中排除 class/method。
- class entry 命中时，该 class 中所有有方法体且可改写的 method 都进入 requested lowering set，除非被 `blackList` 排除。
- method entry 命中时，只考虑该 method；如果它有 Code 则进入 requested lowering set，否则只在 selector eligibility audit 中记录 method kind 和稳定 reason。
- selector 命中但没有 Code 的 abstract/already-native/interface declaration/annotation element 不进入 Code-bearing requested set，也不产生 method status；它们不是 native-lowering failure。

保证：

- requested lowering set 中的每个 method 都必须产生稳定、可解释的结果。
- `nativeLowered` method 必须完成 native lowering 和 selected-target native build。通常它会改写 Java declaration 并进入 native registration；`methodInternalization` 批准的严格子集则删除整个 Java `method_info`、不进入 `RegisterNatives`，并保留native caller closure。该closure可以引用独立hidden implementation，也可以在严格single-call-site coalescing后只存在于caller body。LLVM body、JNI/runtime helper-backed body 和 legal constructor/class-initializer/interface stub 都可以是该状态，只要原业务语义不依赖被复制或嵌入的原 method bytecode。
- final native planner 无法安全生成 LLVM/helper-backed/template body 时，该 method 必须变成 `skipped` + 稳定 reason，并从 rewrite、registration、native source 和 packaging facts 中移除；不得生成或嵌入 fallback class blob。
- `skipped` method 保留原始 bytecode，不进入 `RegisterNatives`，并在 `reports/lowering-report.json` 与 `reports/skipped-method-report.json` 中记录 skip stage、reason code、human-readable reason 和是否影响调用方 lowering。
- excluded method 可以保留为原始 bytecode。
- 不允许静默 skip。任何 skip 都必须出现在 skipped-method report 和 diagnostics 中。

典型 `skipped` 原因：

- unsupported classfile version、preview feature 或 malformed-but-loadable attribute。
- unsupported bytecode opcode、stack map pattern、exception shape、monitor shape。
- 某个 feature 没有硬依赖缺失，但当前 method shape 暂不适用。
- selector 命中的 method 超出当前 compiler capability boundary。

缺少 enabled analysis/protection pass 声明的硬依赖，例如 `classPath`、JDK runtime metadata 或 native toolchain capability，不属于普通 skip；它是 config/preflight error，j2ll 必须提示补齐输入或关闭对应 feature 后退出。

### Skipped-Method Confirmation Gate

Default build 在 final implementation plan 形成后、创建任何 Zig workspace 或发起任何 Zig/toolchain build invocation 之前执行一次确认：

- 若没有 `skipped` method，不打印列表，也不读取 stdin。
- 若存在 `skipped` method，stderr 必须按稳定顺序逐条打印 `<owner>#<name>!<descriptor>`、`reasonCode` 和 human-readable `reason`。
- 随后的 warning 必须明确说明这些方法不会被 native lowered，其原 Java bytecode 会保留在输出 JAR 中。
- CLI 提示 `continue? (Y/N)`，只有显式、大小写不敏感的 `Y` 才可继续。`N`、EOF 或没有可读输入都终止本次 build；不得创建 Zig workspace、调用 Zig 或写 final JAR。
- piped stdin 是正式支持的自动化入口，例如 `printf 'Y\n' | java -jar j2ll.jar ...`。重定向或 CI 环境不得因不是交互 TTY 而绕过确认。
- `--validate` 和 `--dry-run` 不提示、也不读取 stdin。它们不形成 final skipped set；dry-run 固定记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 和 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`，真实 default build 在发现 skipped method 时再确认。
- 所有列表、warning 和 prompt 都写 stderr，stdout 的稳定 `key=value` 输出不受影响。

Stable plain-text shape:

```text
skippedMethod=<owner>#<name>!<descriptor> reasonCode=<code> reason=<text>
warning=<n> selected method(s) will not be native lowered; their original Java bytecode will remain in the output JAR.
continue? (Y/N)
>
```

构建失败或用户拒绝继续时：

- 不输出成功态 final jar。
- 输出当前阶段已经能够生成的 diagnostics、resolved config、lowering/skipped reports 和 debug artifacts。

## Config File

默认配置文件名是：

```text
Config.json
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
      "controlFlowFlattening": true,
      "fakeBranches": true,
      "basicBlockSplitting": true,
      "constantEncryption": true,
      "stringEncryption": true,
      "methodInlining": true,
      "methodSplitting": true,
      "callIndirection": true,
      "fieldInternalization": false,
      "methodInternalization": false,
      "publicMethodInternalizationAllowList": [],
      "methodTableHiding": true,
      "blockNameObfuscation": true
    },
    "llvm": {
      "enabled": true,
      "nameObfuscation": true,
      "opaquePredicates": true,
      "blockLayoutPerturbation": true,
      "indirectCalls": true,
      "globalLayout": true
    },
    "binary": {
      "enabled": true,
      "hideInternalSymbols": true,
      "strip": true,
      "removePdb": true,
      "symbolAudit": true,
      "retainUnwindInfo": false
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
java -jar build/cli/j2ll.jar --validate --config docs/examples/minimal-config.json
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
- `j2ll [--config <config.json>] [--validate|--dry-run] [--debug]`

`--config` selects the config file; without it the CLI reads `Config.json` from the current directory. `--validate` only checks config and does not create a workspace or pipeline artifacts. `--dry-run` writes reports for config, selector expansion and target preflight, but never invokes managed Zig/native build and never writes a final JAR. With neither mode flag, the CLI runs the full build pipeline. `--debug` enables all five effective intermediate switches for the run (`enabled`, debug dumps, per-class IR, per-class LLVM and per-class C) and forces effective unwind retention; it does not request native debug symbols.

The default build mode and `--dry-run` allocate a workspace automatically at `<resolved-outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]/`. `--validate` allocates none. The build mode and failure-producing commands use stable exit codes:

- `0`: success.
- `2`: config validation failure.
- `3`: frontend, parse, CFG, lowering, validation or LLVM emission failure.
- `4`: toolchain, native build or symbol audit failure.
- `5`: packaging or signing failure.
- `6`: artifact audit failure.
- `7`: strict release-readiness failure.
- `1`: unexpected internal error or an uncategorized fatal diagnostic.

On success stdout is intentionally short and includes only the final output JAR path, reports directory, summary report path and report index path. Dry-run success prints `dryRunReport=...`, `reportsDir=...`, `summaryReport=...` and `reportIndex=...`. Full-build progress is written only to stderr. Interactive terminals use optimized legacy phase regions: compiler work is shown as `Read bytecode` / `Lower to IR` / `Emit LLVM IR`; native preparation uses four independent real-work rows, `Generate C` / `Audit native` / `Write LLVM` / `Prepare Zig`, followed by `Build native`. Managed Zig resolution is shown only in the transient `Stage` row as `preparing managed Zig toolchain`, never as a synthetic progress bar. The preparation rows remain completed while the active target graph uses one aggregate `Build native` row and one stable row per selected target; packaging/audit/report writing is shown as `Finalize JAR`. Each target percentage is exactly the number of completed Zig build-graph work units divided by the total graph units planned for that target. Large source sets are deterministically balanced into at most 64 observable compile units per target so marker count and polling I/O remain bounded. `BUILDING`, `LINKING`, and `COMPLETED` states (rendered as `building`, `linking`, and `completed`) must follow real graph boundaries, not console-text parsing or elapsed-time estimates. Entering `LINKING` need not advance the counter, so the bar may remain at the final compilation percentage until linking finishes. A target reaches `100%` / `COMPLETED` only after its final non-empty DLL/SO/dylib has been installed at the planned flat workspace output path. The aggregate `Build native` bar remains a real completed-target count; as soon as every target is complete, all target rows collapse immediately to one aggregate completed row before finalization begins. A phase transition completes the previous region and starts the next region; only the active region is erased and redrawn in place. Normal-width terminals use 28-character bars, while narrow terminals may shorten the bar before truncating the label, real count, or useful detail. Redirected/CI output receives exactly one control-sequence-free `[current/total]` line per high-level stage plus the short success result; native-preparation and target progress callbacks do not add log lines, so the 13-stage plain-output contract remains stable. Method lowering and LLVM emission report real current/total counts, including honest zero-work states. Managed Zig remains one matrix-wide invocation and may schedule independent targets concurrently. The TUI must not concatenate all target names into one detail, parse unstable Zig console text, claim an execution order, or describe graph-unit percentages as Zig/Clang/LLVM compiler-internal progress. The skipped-method list, warning and confirmation prompt are emitted after final planning and before this native region begins; they are stderr diagnostics, not progress rows, and also appear in redirected output. On failure or declined confirmation the complete active progress region is only cleared and terminated before stderr prints the primary human-readable failure; the renderer must not insert a redundant `BUILD FAILED` or Gradle-style actionable-stage summary ahead of that diagnostic. One short `hint=...` line is printed when available, followed by the reports directory, summary report path and report index path. Detailed diagnostics remain in `reports/*.json`; CLI output must not dump long JSON bodies. Release-readiness failures additionally print `releaseReadinessReport=<path>` and at most the top three `missingEvidence` entries from `reports/release-readiness.json`.

Compile-unit balancing must keep every observable unit homogeneous by source kind.
Generated C units use Zig `ReleaseSmall`; per-class LLVM units and the final link
module use `ReleaseSafe`. These modes are fixed implementation policy, not
CLI/config fields. A C/LLVM mixed unit is invalid because it would make one input
class inherit the other's optimization mode.

Minimal command:

```bash
j2ll --config config.json
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

Recommended default is `PARTIAL_WORLD`: application classes from the input JAR are analyzed directly, `classPath` is used when present, and missing external/library facts stay conservative. Any analysis/protection pass that requires a stronger world model must declare that requirement before execution. `fieldInternalization` and `methodInternalization` are independent shared confirmation-gate consumers: outside `CLOSED_WORLD`, build asks once for each enabled feature whether to continue with its feature-scoped current-input-JAR-only analysis. These execution decisions never mutate the configured `worldModel`. The method decision covers private/protected and exact-allowlisted public static candidates; public instance internalization remains limited to declared `CLOSED_WORLD`.

World model validation matrix:

| Value | Minimum required inputs | Main consequence |
| --- | --- | --- |
| `CLOSED_WORLD` | input JAR, every application/library entry needed by the analysis in `classPath`, and the user's complete-world assertion; feature reports still enumerate observed escape hatches | Historical wire name for the user's complete-world assertion. It directly satisfies field/method internalization and can enable aggressive devirtualization. Public removal additionally requires an exact allowlist entry; public instance also requires a parse-complete combined input/classPath hierarchy/call world and exact same-owner call sites. For exact-allowlisted public method internalization, j2ll hard-rejects resolved exact method observers and known external Java entries, but reports unsupported/unbounded reflection/JNI/agent surfaces as accepted risks because it cannot prove an omitted observer does not exist. Other features may retain stricter observer gates. |
| `PARTIAL_WORLD` | input JAR; optional `classPath` and JDK metadata | Recommended default. Missing external metadata is allowed but produces conservative external nodes; unsafe method shapes become `skipped`. |
| `JDK_EXTERNAL_WORLD` | input JAR plus enough JDK identity metadata to classify JDK classes | Application classes are analyzed; JDK methods use intrinsics, runtime/JNI helpers or ordinary JVM dispatch from native code when supported. |
| `UNKNOWN_DYNAMIC_WORLD` | input JAR only | Reflection, custom classloaders and generated classes are assumed possible. Analysis must stay conservative and avoid protection decisions that require a complete classpath. |

Schema version 1 never stores a second copy of selected method bytecode in native libraries. There is no `nativeEmbeddedClassBlob`, fallback-class storage mode, `fallbackMode`, or `requiredNative` field. Unsupported selected methods stay as ordinary Java bytecode in their original class and are reported as `skipped`.

`outputDirectory`

Directory for build workspaces. Required. Build and dry-run create `build_yyyy-MM-dd_HH-mm-ss[-n]` under the resolved directory; the numeric suffix avoids a same-second collision. Validate creates no workspace.

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
- If a selector matches abstract methods, already-native methods, interface methods without Code, or annotation elements without Code, those results remain outside the Code-bearing requested set and are recorded only in selector eligibility evidence with a stable no-Code/method-kind reason.
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

Managed Zig `0.15.2` maps these fields to a fixed structural cross-build matrix:

| Config field | Zig target query | ABI / minimum |
| --- | --- | --- |
| `windowsX64` | `x86_64-windows-gnu` | MinGW/UCRT GNU ABI |
| `windowsArm64` | `aarch64-windows-gnu` | MinGW/UCRT GNU ABI |
| `linuxX64` | `x86_64-linux.3.2-gnu.2.17` | Linux 3.2, glibc 2.17 |
| `linuxArm64` | `aarch64-linux.3.7-gnu.2.17` | Linux 3.7, glibc 2.17 |
| `macosX64` | `x86_64-macos.10.15` | macOS 10.15 |
| `macosArm64` | `aarch64-macos.11.0` | macOS 11.0 |

All selected targets are compiled and linked by one generated `build.zig` and one matrix-wide `zig build` invocation. Successful structural cross-build means the target DLL/SO/dylib was produced and passed format/architecture/export audit; the support matrix records this as `ZIG_CROSS_TARGET_SUPPORTED`. It does not by itself claim child-JVM execution on a non-host OS. Non-host runtime E2E remains separate release evidence with reason `CROSS_TARGET_RUNTIME_E2E_PENDING`.

Java support tiers are compiler-development and release-evidence categories, not a user-selectable config gate. j2ll inspects each selected Code-bearing method and records either `nativeLowered` or `skipped`; build failures remain separate diagnostics.

j2ll also derives its logical Zig/native build name internally as an exact 16-character lowercase hexadecimal token, for example `408cc4b89702abf5`. It has no `j2ll` or other semantic prefix, is not configurable, and does not affect the fixed per-target JAR resource filenames below. PE uses `<hash>.dll`; ELF and Mach-O may add the platform-standard `lib` prefix. The token varies between default randomized builds and remains stable in explicit-seed reproducible mode.

`embeddedLibraryDirectory`

Package path inside the output jar where selected target dynamic libraries and the generated runtime loader are stored. Default recommendation is `native0`. This value is also the loader's JVM package/internal-name prefix, so it must be a canonical ASCII Java internal package path matching `[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*`. Empty segments, leading/trailing `/`, `.`, `..`, backslashes, and the reserved `java[/...]` and `META-INF[/...]` namespaces are invalid. Invalid values fail config validation with `INVALID_EMBEDDED_LIBRARY_DIRECTORY`.

Every build reserves `<embeddedLibraryDirectory>/Loader.class`. If the input JAR already contains that base entry, packaging fails with `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION`; if any `META-INF/versions/**/<embeddedLibraryDirectory>/Loader.class` entry could shadow it, packaging fails with `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW`. Both checks run before managed Zig/native build and no final JAR is written.

Different output artifacts that use the same directory therefore request the same loader binary name. Loading such artifacts through one defining `ClassLoader` is a known boundary; choose an application-unique `embeddedLibraryDirectory` when they may coexist. Independent `ClassLoader` instances keep separate loader classes and state.

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
- Zig compiles/links all buildable selected target dynamic libraries. Managed Zig `0.15.2` currently declares all six fixed targets above structurally buildable; non-host selection alone is not a failure condition. Schema v1 records every selected target in preflight/report, and selected targets are required by default. A target with an actual capability, preflight, compile or link failure is reported in `failedTargets` with `ZIG_TARGET_UNBUILDABLE`, includes required/optional state, exact Zig target query, expected library path/name, failure kind, exact reason and build log tail, and makes the pipeline fail; optional/report-only target simulation belongs only in focused toolchain tests.
- Per-class `.ll`, Zig-managed `.o`, JNI wrapper C and runtime helper C are Zig toolchain inputs.
- Cross-target C compilation uses the current JDK's platform-neutral `jni.h` plus a generated target-portable `jni_md.h`; it must not reuse the host platform's `jni_md.h` ABI definitions for non-host targets. Target preflight fails before Zig invocation when the current runtime does not provide `include/jni.h`.
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

The CLI `--debug` flag overrides all five switches to `true` and forces effective unwind retention for that run. This is an intermediate-artifact diagnostic mode; native libraries are still release-style builds without native debug symbols.

`protection`

Controls SSA IR protection, LLVM module model protection and binary hardening. Required field. The recommended default config enables protection and all non-semantic-surface-changing implemented passes. `fieldInternalization` and `methodInternalization` are implemented but default to `false` because they deliberately remove approved members from Java reflection.

Fields:

- `enabled`: master switch for all protection layers.
- `seed`: optional fixed seed. If `null`, each default build creates a fresh 256-bit randomized build root shared by every target in that matrix. A non-null value selects reproducible mode. Workspace reports record only the seed mode and a hash-only identity, never the raw configured seed or randomized root; the final JAR contains no j2ll-private metadata subtree. `--validate` does not persist a build identity; `--dry-run` does not promise the same native physical layout as a later default build.
- `ir`: SSA IR protection settings.
- `llvm`: LLVM module model protection settings.
- `binary`: binary visibility/strip settings.

Protection availability behavior:

- Current schema v1 IR/LLVM pass fields all have real implementations. If a future known field is present in a build where its pass is unavailable, j2ll emits a warning and ignores that pass rather than silently claiming it ran.
- If a protection pass is implemented but not applicable to a specific method, j2ll skips that pass for that method and emits a warning; protection-pass inapplicability alone does not change a `nativeLowered` method into method status `skipped`.
- If a protection pass declares a hard requirement such as `classPath`, JDK metadata or target toolchain support and the requirement is missing, preflight emits a clear error and exits. The error must name the pass and tell the user to provide the missing input or disable that pass.

The previously tracked work items are now wired as bounded v1 subsets: IR/program/final-plan `methodInlining`, `methodSplitting`, `callIndirection`, `fieldInternalization`, `methodInternalization`; packaging/native registration `methodTableHiding`; LLVM `opaquePredicates`, `blockLayoutPerturbation` and `globalLayout`. Their exact runtime and cross-target evidence is tracked separately in `docs/protection-implementation-checklist.md`; structural cross-link evidence does not imply non-host JVM execution or stable optimized machine-code retention.

### Protection IR Fields

`protection.ir.enabled`

Master switch for SSA IR protection passes.

Pass fields:

- `controlFlowFlattening`: dispatcher-based CFG flattening.
- `fakeBranches`: run the independent `FakeBranchesPass`, inserting a deterministic predicate gate and detour where the IR shape is safe. This changes protected IR, but managed Zig uses `ReleaseSafe`; when no suitable dynamic parameter exists and the pass uses its constant predicate fallback, LLVM may fold away the native branch. The field therefore does not guarantee that every emitted binary retains an opaque branch.
- `basicBlockSplitting`: run the independent `BasicBlockSplittingPass`, splitting an eligible basic block at a deterministic instruction boundary without also inserting a fake branch.
- `constantEncryption`: encode/decode numeric constants.
- `stringEncryption`: encrypt string literals and emit decode helpers.
- `methodInlining`: inline the bounded pure-scalar SSA subset of statically proven `static` or same-owner private-self direct callees. Reflection/exception/monitor/JMM/call/field-sensitive callees are skipped per pass.
- `methodSplitting`: outline a bounded pure-scalar single-block suffix with explicit scalar live-in and one scalar live-out into a compiler-internal LLVM helper. It does not add a Java method.
- `callIndirection`: attach a typed IR table/dispatcher plan to proven same-owner static/private-special calls, then lower that explicit plan to hidden LLVM pointer tables. The current per-owner backend rejects virtual/interface (including devirtualized single-target) and cross-owner direct calls with `IR_CALL_INDIRECTION_BACKEND_UNSUPPORTED_SHAPE`; it does not reinterpret their JVM bridge semantics.
- `fieldInternalization`: migrate strictly eligible `private static` primitive/reference state out of the Java field declaration and remove the field from the output class. Primitive values use descriptor-aware native raw-bit slots; references/arrays use a JVM-managed per-defining-Class `ClassValue<Object[]>` sidecar. Default is `false`; `CLOSED_WORLD` runs directly, while another world requires build-time Y approval for current-JAR-only analysis. See the dedicated contract below.
- `methodInternalization`: remove a strictly eligible Java method declaration and its `RegisterNatives` binding after final planning proves that every observed entry is already inside the LLVM-native closure. Private/protected use the ordinary closed-world/current-JAR-only policy. Public additionally requires an exact allowlist entry: public static may use declared closed world or the current-JAR-only Y decision, while public instance requires declared `CLOSED_WORLD`. Its hidden LLVM implementation remains in every selected native library. Default is `false`. See the dedicated contract below.
- `publicMethodInternalizationAllowList`: required `array<string>`, with examples defaulting to `[]`. Every element must be a unique, wildcard-free exact `<owner>#<name>!<descriptor>` selector; class selectors, wildcard owners and duplicates are config errors. It authorizes consideration of that public method but does not override the public-instance declared-world rule, resolved exact-observer rejection, caller closure or final-path validation. The resolved-config report preserves the exact declared entries for audit.
- `methodTableHiding`: derive a build-diverse owner-local physical registration order and construct a transient `JNINativeMethod[]` with straight-line assignments inside each owner registration window. Opaque binding tokens remain report-only evidence; generated C/native emits no persistent token/function-pointer table or runtime join. JVM `RegisterNatives` still receives the real owner/name/descriptor at runtime.
- `blockNameObfuscation`: deterministically replace IR basic-block names and remap all terminator, exception-edge and exception-site handler references.

Each pass field is a required boolean: `true` enables the pass and `false` disables it. `fieldInternalization` and `methodInternalization` default to `false`; the other examples may enable the implemented bounded subsets. `publicMethodInternalizationAllowList` is the required non-boolean authorization list beside those fields and defaults to `[]`. `fakeBranches`, `basicBlockSplitting` and `blockNameObfuscation` are separate fields, passes and protection-report rows.

#### `fieldInternalization` Contract

Enabling this field is an explicit acceptance that a plan-approved field will no longer be visible to `Class.getDeclaredField(s)`, serialization field discovery, agents or other Java bytecode after rewriting. The current v1 implementation therefore fails closed:

- If `worldModel` is not `CLOSED_WORLD`, a real build writes the warning to stderr and asks `fieldInternalization requires CLOSED_WORLD, continue? (Y/N)`. Y authorizes only this feature and invocation; N or EOF exits with code 2 before workspace allocation, pipeline execution or Zig. A terminal is detected through `System.console` with an `isatty(stdin)` fallback for PTY/IDE/MSYS-style launches; pre-supplied stdin such as a piped `Y` is also accepted. An unattended/CI launch with no buffered answer becomes immediate EOF instead of hanging. Invalid interactive input is re-prompted.
- Y does not rewrite `worldModel`. The field report retains the configured value, records `scope=CURRENT_JAR_ONLY`, `authorization=USER_CONFIRMED`, `classPathAnalyzed=false`, and `externalObserverPolicy=OUT_OF_SCOPE_USER_ACCEPTED`.
- `--validate` and `--dry-run` do not execute field analysis and therefore do not consume stdin. They succeed when the remaining config/preflight is valid, emit the `FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD` warning, and record `decision=confirmationRequired`; the subsequent real build still asks.

- Mutable candidates are input base-class `private static boolean/byte/short/char/int/long/float/double` or JVM reference/array fields. The exact supported JVM descriptors are `Z/B/S/C/I/J/F/D`, `L...;`, and `[...]`. They must be non-final, non-volatile, non-synthetic/non-enum-generated and have no ConstantValue, Signature, annotation or type annotation.
- A separate no-runtime-storage path accepts input-base `private static final` classfile `ConstantValue` declarations. Explicit same-owner reads of `Z/B/S/C/I/J/F/D` are folded to exact SSA constants only when every accessor is final `LLVM_NATIVE_PATH`; any write, cross-owner/non-LLVM accessor or dynamic observer keeps the field. Explicit String `GETSTATIC` is not folded because a newly constructed `jstring` cannot prove the original intern/object-identity semantics. A primitive or String ConstantValue declaration with no remaining field reference may be removed directly because javac/classfile consumers already carry the constant at their use sites. Constant values are never serialized to reports.
- For both paths, the owner must have no disqualifying `<clinit>` access, serializable semantics or multi-release counterpart; unsupported metadata remains fail closed.
- Every observed access must come from a same-owner static or instance method whose final status is `nativeLowered` and whose final implementation path is an `LLVM_NATIVE_PATH` eligible for the internalized storage ABI. An instance wrapper passes the field's declared defining `jclass`, not the receiver runtime class, so the static state is not split by subclass. Classpath, cross-owner/nestmate, unselected, `skipped` or non-LLVM access keeps the field in the JVM. There is no fallback-bytecode accessor rewrite or fallback sidecar path.
- Declared closed-world analysis scans input plus supplied classpath for field bytecodes, LDC field handles and invokedynamic/ConstantDynamic bootstrap arguments, resolving symbolic owners to the declaration. Current-JAR-only analysis deliberately does not parse configured classpath entries. It ignores unresolved external field references whose symbolic owner is outside the input JAR, while unresolved references whose symbolic owner belongs to the input JAR still block approval.
- Reflection, Unsafe, VarHandle, MethodHandle, JNI/native loading, serialization, agent/instrumentation or dynamic-class-loading surface blocks approval.
- Approved mutable IR accesses carry an opaque slot id plus an exact storage kind. Primitive storage is keyed by the actual defining `jclass` using `jweak` and `IsSameObject`, cleans stale weak references lazily, and uses relaxed atomic `uint64_t` raw-bit storage to avoid native data-race undefined behavior while retaining ordinary-field semantics. Boolean uses its low bit, byte/short/char use JVM truncation plus sign/sign/zero extension, and float/double use LLVM bitcasts so NaN payloads and negative zero survive. Compile-time constants instead report `internalizationStorage=COMPILE_TIME_CONSTANT`, `storageLocation=ssaFoldedNoRuntimeStorage`, and have null sidecar/slot identifiers.
- Reference/array storage remains on the JVM heap. The single generated `Loader.class` is conditionally made a `ClassValue` whose value is an `Object[]` sidecar for each defining `Class`. `ClassValue` is the cross-call cache; each native function activation uses a native-stack cache cell to obtain the JNI local sidecar reference lazily on its first executed field access, reuse it for the remaining accesses, and release it on every exit. A branch that does not execute a field access does not call `ClassValue.get`. No strong native global reference is created.
- The final native implementation plan is revalidated before Zig; packaging rechecks the field declaration before removal; artifact audit rejects any remaining declaration, bytecode access or field handle/bootstrap reference.

Ineligible fields stay in the output class and continue through the existing JNI field helper path. Protection inapplicability does not change method lowering status.

#### `methodInternalization` Contract

Enabling this field explicitly accepts that an approved method is absent from `Class.getDeclaredMethod(s)`, MethodHandle lookup and other Java metadata observers. It does not introduce a third lowering outcome: an approved method remains `nativeLowered`, with `rewriteStrategy=internalNativeOnly`, `javaMethodPresent=false` and `registrationPresent=false`. Its physical `retentionMode` is normally `internalNativeOnly`; a strict unique-call-site coalescing may change only that report field to `coalescedNativeOnly` and add `coalescedInto=<caller-key>`.

- If `worldModel` is not `CLOSED_WORLD`, a real build asks `methodInternalization requires CLOSED_WORLD, continue? (Y/N)`. Y authorizes only this feature and invocation to inspect calls, overrides and metadata inside the current input JAR; configured `classPath`, JAR-external callers/subclasses and external reflection/JNI/agent observers remain out of scope. N/EOF exits before pipeline/Zig work. Validate and dry-run emit the requirement but do not read stdin.
- That Y applies to private/protected candidates and exact-allowlisted public static candidates. Public instance candidates must match one exact `publicMethodInternalizationAllowList` entry and the configured world must be declared `CLOSED_WORLD`; current-JAR-only never authorizes public instance removal.
- The final implementation must already be an ordinary Code-bearing `LLVM_NATIVE_PATH` method using `nativeOriginal`. Constructors, class initializers, interfaces, synchronized, bridge/synthetic, abstract/already-native and multi-release owner shapes are not removed.
- V1 accepts `private` or `protected` static methods, including protected static calls from another selected owner. It also accepts private/protected instance methods only when every caller has the same owner and each `invokespecial` or `invokevirtual` edge is proven exact in the effective scope. Cross-owner instance and interface dispatch remain Java-visible.
- Exact-allowlisted public support includes public static and same-owner exact public instance. Public instance does not require a final method or final defining class, and a potentially overridable slot is not itself a rejection reason. Under declared `CLOSED_WORLD`, the pipeline parses input plus every configured classPath entry; each public-instance call site must still resolve exactly to the candidate and every caller must be same-owner. An actual override that makes dispatch non-exact prevents removal.
- Every observed caller must itself have a final `LLVM_NATIVE_PATH` implementation and a validated native direct/dispatch route. Zero callers, an unselected/skipped/template caller, non-exact target, a resolved exact reflection/MethodHandle/Handle/bootstrap/ConstantDynamic observer, `EnclosingMethod` reference, launcher/agent entry, or a closed-catalog JVM/JDK callback entry keeps the Java method. The callback catalog covers exact Object virtual, Runnable/Callable, Thread/TimerTask, serialization, Comparator, common `java.util.function` and primitive-function contracts only when the declaring owner really implements or inherits the catalog type and the descriptor matches exactly; it is not a blanket override-slot veto. Third-party framework callbacks outside that closed catalog and unsupported or unbounded reflection/JNI/agent observation remain user-accepted risk and must not be reported as proven absent. For current-JAR-only public static, configured classpath and every JAR-external caller/observer remain explicitly outside the analysis scope.
- Approved static/instance reference-returning or JNI-sensitive calls use a nested JNI local frame. Pending exceptions are promoted out of that frame and restored before returning to the outer native activation. Cross-owner static calls resolve the defining `jclass`; native code never reads a Java object layout directly.
- Final-plan validation removes the binding from `NativeRegistrationPlan` and retains the logical native implementation. A subsequent immutable physical-retention plan may merge a bounded pure-scalar/non-throwing target with exactly one direct call site into its caller; multi-layer chains are processed bottom-up and every descendant is rehomed to the final physical root. Otherwise it retains the hidden implementation and any internal-call wrapper actually needed by callers. Packaging atomically removes the exact `MethodNode`; artifact audit rejects any residual declaration, `MethodInsn`, method `Handle`, bootstrap/ConstantDynamic or `EnclosingMethod` reference. For `coalescedNativeOnly`, audit additionally rejects a standalone callee LLVM function/declaration/reference, generated-C wrapper or workspace symbol.

A `KEPT` decision is an exact no-op: the existing `nativeOriginal` rewrite and `RegisterNatives` behavior remains in force.

### Protection LLVM Fields

`protection.llvm.enabled`

Master switch for LLVM module model protection passes.

Pass fields:

- `nameObfuscation`: de-semanticize LLVM function/global names.
- `opaquePredicates`: add a defined-integer, side-effect-free always-true gate to eligible conditional branches. The current model transform is real, but `ReleaseSafe` may fold it out of the final binary.
- `blockLayoutPerturbation`: keep the entry block fixed and reorder non-entry LLVM block emission for eligible functions. It does not promise final linker/machine-code address order.
- `indirectCalls`: lower eligible direct calls to native-level indirection.
- `globalLayout`: reorder only complete `private`/`internal` LLVM globals among their existing candidate emission slots. Definitions, references, alignment/section/mutability and non-local retention roots remain unchanged; generated-C tables are outside this pass.

Each pass field is a boolean: `true` enables the pass and `false` disables it.

LLVM protection operates on `backend.llvm.model`. It must not mutate `.ll` text using string replacement.

`visibilityHardening` is not a schema v1 field. Java implementation functions and protection tables use hidden/internal LLVM linkage as a non-disableable backend baseline, and every final dynamic library must pass the export allowlist audit regardless of `protection.enabled` or `protection.llvm.enabled`.

Current schema v1 implements `indirectCalls` for same-class selected static/private direct LLVM calls by inserting hidden signature-group function-pointer tables named `j2ll_cit_<sha256>` into the LLVM module model and the Zig workspace `.ll` input. The protection report uses reason code `CALL_INDIRECTION_TABLE` when a table is emitted and `CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE` when table mode has no eligible direct call. A hidden dispatcher switch fallback named `j2ll_cid_<sha256>` remains available and reports `CALL_INDIRECTION_DISPATCHER`. Table, dispatcher and Java implementation symbols are internal/native hidden symbols and must not appear in `reports/symbol-audit.json` as dynamic exports.

The IR `callIndirection` field is independent: it produces `IR_CALL_INDIRECTION` semantic-plan evidence and `IR_CALL_INDIRECTION_BACKEND` evidence when the explicit plan becomes an internal `j2ll_ircit_<sha256>` LLVM table. The LLVM `indirectCalls` pass leaves those already-indirect sites alone.

`opaquePredicates`, `blockLayoutPerturbation` and `globalLayout` all run before text emission on `LlvmModule`, validate input and output, and emit `RAN` / `SKIPPED` / `FAILED` report rows. Their current bounded model semantics, passing Windows real-Zig host and six-target structural evidence, and remaining optimized-machine/non-host runtime limits are listed in `docs/protection-implementation-checklist.md`.

### Protection Binary Fields

`protection.binary.enabled`

Master switch for binary hardening.

Fields:

- `hideInternalSymbols`: request the configurable binary-hardening layer for additional internal helper/symbol hiding; Java implementation/protection symbols remain hidden/internal as a mandatory backend baseline even when this field or `protection.binary.enabled` is `false`.
- `strip`: strip unneeded symbols in release artifacts.
- `removePdb`: do not package Windows PDB files and remove accidental `.pdb` outputs.
- `symbolAudit`: request binary-hardening audit reporting; the final export allowlist audit remains a mandatory success gate and cannot be disabled by this field or `protection.binary.enabled`.
- `retainUnwindInfo`: request final-native unwind retention. When `false`, Linux/macOS generated C is compiled with `-fno-unwind-tables` and `-fno-asynchronous-unwind-tables`; each final canonical LLVM module is also analyzed structurally, and only a module whose function/instruction evidence is entirely `PROVEN_ABSENT` receives a target-selectable `nounwind` text variant. `REQUIRED`、`UNKNOWN`、proof-incomplete modules and unmodeled `.o` inputs retain unwind metadata. Windows always selects retention for SEH correctness. `--debug` forces effective retention without mutating the requested JSON value. The build does not rely on a Zig module flag or C compile flags to rewrite `.ll` files supplied through `addObjectFile`.

The target manifest records generated-C retention, LLVM module/omitted/retained counts,
unmodeled object input count, final omission expectation and the effective reason. When a
Linux/macOS target is proven to expect complete omission, the linked artifact must pass a
blocking section audit: non-empty ELF `.eh_frame`/`.eh_frame_hdr` or Mach-O
`__eh_frame`/`__unwind_info` fails the native build. PE `.pdata`/`.xdata` is also inspected
and reported, but Windows never requests its removal. A config value of `false` therefore
means “omit wherever the final proof permits”, not “silently delete unwind information even
when native EH or an opaque object input is present”.

## Output Workspace

Build and dry-run create automatically:

```text
<resolved-outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]/
```

Validate creates no workspace. The optional numeric suffix is added when the timestamped name already exists.

Required top-level layout:

```text
build_yyyy-MM-dd_HH-mm-ss[-n]/
  config.resolved.json
  <input-jar-file-name>
  reports/
    artifact-audit.json
    diagnostics.json
    failure-report.json
    field-internalization-report.json
    skipped-method-report.json
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
    x64-windows.dll
    arm64-windows.dll
    x64-linux.so
    arm64-linux.so
    x64-macos.dylib
    arm64-macos.dylib
    zig-workspace/
      evidence/
        optimized-assembly/<target>/<c-id>.s
    zig-cache/
  intermediates/
    classes/
    runtime/
    dumps/
  logs/
    zig-build.log
```

`config.resolved.json`

Fully resolved config with defaults, absolute paths, a hash-only protection seed identity, resolved target list and normalized selectors. The raw protection seed is not written to this report.

`<input-jar-file-name>`

Final repacked JAR at the workspace root. This is the primary output artifact and is present only after a successful build.

The final JAR never contains `META-INF/j2ll/**`, including case variants and multi-release counterparts. Reprocessing an older output strips that reserved private subtree, and future packaging code is forbidden from adding it. `META-INF/MANIFEST.MF` remains a normal preserved JAR artifact; any manifest section whose `Name` points into the removed private subtree is stripped while unrelated main attributes and sections remain. Build identity, report inventory and target hashes stay in the workspace reports rather than being published inside the application JAR.

`logs/zig-build.log` is written when the managed Zig build is invoked and retains its command plus compiler/linker output for failure diagnosis. `logs/zig-progress/` is not an output artifact: it contains short-lived build-graph markers only while the matrix invocation is running and the whole directory is deleted after success, failure or interruption.

`native/zig-workspace/evidence/optimized-assembly/<target>/<c-id>.s` is workspace-private build evidence. Each generated-C input is compiled exactly once through the managed Zig compiler at `-Oz` to this assembly; that same `LazyPath` is both consumed by the registration machine-topology gate and passed to `addAssemblyFile` as the actual link input. A second C compilation and `getEmittedAsm()` are forbidden because neither would bind the audit to the linked machine code. Missing, duplicate, ambiguous or unsupported evidence fails the build after Zig returns and before native artifacts are collected. These files are never copied into the final JAR or serialized into a report.

`native/zig-workspace/j2ll-build-manifest.json` keeps the existing `cSources` string array and target-level machine-outliner fields for append compatibility; those target fields describe the target-default policy, not every C input. Each target also writes `machineOutlinerPolicyScope: "PER_C_INPUT"` and a `cSourceMachinePolicies` array. Every row binds `source` and `compileInputId` to its closed `mode`, effective enabled state, threshold, reason, exact `machineOutlinerCFlags` and `optimizedAssemblyEvidence`. The registration-bearing wrapper row must be `REGISTRATION_CONTROL_OUTLINER_FORBIDDEN` and explicitly select `-mllvm -enable-machine-outliner=never`; all other C inputs remain `TARGET_DEFAULT`. Both the manifest and build graph consume the same immutable compile-input inventory; the binding is derived from the authoritative wrapper emission path and must survive source sorting/batching without relying on a file name or reconstructed `c-id`.

`native/zig-cache/**` is Zig-owned duplicate build cache, not a canonical
delivery or audit surface and is never packaged. Canonical native plaintext
surfaces are generated C/LLVM/build files under `native/zig-workspace/**`,
per-class generated C/LLVM under `intermediates/classes/**`, and every flat
final library under `native/*.{dll,so,dylib}`. Artifact audit excludes the
cache copy to avoid reporting the same linked image twice; it never excludes
the flat final library.

`reports/diagnostics.json`

All diagnostics with stable ordering. Each entry includes a short user-facing `hint` when the reason code has a stable remediation path, for example selector grammar, missing `schemaVersion`, Zig target preflight, signed input policy or artifact-audit plaintext leak.

`reports/failure-report.json`

Written for failed config or pipeline runs. It summarizes error diagnostics with stable `primaryDiagnosticId`, `stage`, `reasonCode`, `message`, `hint`, affected selector/method/target fields where available, and `finalArtifactWritten=false`. It is a failure hygiene sidecar; successful runs may omit it.

`reports/artifact-audit.json`

Artifact audit result. Successful pipeline runs audit the output JAR and embedded native resources for legacy/fallback generated `.class` entries, legacy output paths, exactly one correctly named Java 17 `<embeddedLibraryDirectory>/Loader.class`, absence of `defineHiddenFallback`, the retired `J2llFallbackSupport.class`, `J2llNativeLoaderSupport.class`, and `j2ll/generated/**/NativeLoader.class` entries, native library resource placement under `embeddedLibraryDirectory`, embedded native SHA-256 consistency with `packaging-report.json`, complete absence of the private `META-INF/j2ll/**` subtree and its manifest references, workspace embedded-library/target-artifact consistency, and an exact shared `[0-9a-f]{16}` logical library name for all built targets. It also checks hidden/protection/internal symbol export leaks (`j2ll_f_`, `j2ll_cit_`, `j2ll_cid_`, `j2ll_ircit_`, `Java_`), Windows PDB exclusion and sensitive-plaintext facts in generated C/LLVM/native workspace artifacts and `lowering-report.json`. It verifies every registered `nativeLowered` method's body/registration closure, every standalone `internalNativeOnly` hidden body/native-caller closure, every `coalescedNativeOnly` caller-carried implementation plus zero standalone callee LLVM/C/workspace surface, and every `skipped` method's retained original bytecode with no native registration or embedded bytecode copy. When field internalization approves a field, audit blocks on any residual output-class declaration, `FieldInsn`, LDC field handle, invokedynamic/ConstantDynamic bootstrap field reference, or accessor whose final status is not `nativeLowered`. The report includes `checkedSensitiveFacts`, `observedOnlySensitiveFacts` and `skippedSensitiveFacts`; each entry is hash-only and includes `literalHash`, `sourceMethod`, `passName`, `pathKind`, `gateMode`, `sourceSurface`, `reason` and `promotionReason`. `LLVM_NATIVE_PATH`, `TEMPLATE_JNI_PATH_STABLE_SURFACE`, StringConcat constant carrier, and `NATIVE_METADATA_STRING` facts are blocking on their connected surfaces. Output `.class` plaintext auditing is semantic rather than a raw constant-pool byte search: it checks executable String LDCs, invokedynamic/ConstantDynamic bootstrap String arguments, String `ConstantValue` data and annotation String values, while required class/member/field names and descriptors in structural UTF8 metadata are not treated as business-string carriers. If a class cannot be parsed, audit falls back to the conservative raw-byte scan; non-class JAR resources continue to use raw scanning. `NATIVE_METADATA_STRING` covers registration owners, sufficiently distinctive member names, referenced internal class names and native runtime error text; generated C stores those JNI-required bytes as build-specific encoded arrays and performs every ciphertext read through a volatile-qualified runtime boundary so an optimizing compiler cannot reconstruct plaintext constants in the final image. Registration rollback/exception-restore diagnostics use the registration text domain and are decoded only in their `FatalError` path; the generated-C hardening gate reports `STABLE_REGISTRATION_DIAGNOSTIC` if a historical stable anchor or any direct/adjacent `FatalError` C string literal reappears. Owner-name scratch is cleared immediately after class lookup, and the generated runtime checks both `Throw` status and pending-exception evidence when restoring the original registration failure. Generated wrapper/LLVM/bootstrap identifiers are hash-only, including emitted-LLVM string-token SSA value names. This is an at-rest static-string boundary, not a claim that runtime memory or JNI arguments are secret. Short/common literals that can naturally collide with report field names, JVM metadata or runtime support names remain hash-only observed evidence. Reflection/lambda/MethodHandle metadata facts remain `observedOnly`. The checks array records surface coverage for generated C, per-class LLVM `.ll`, `build.zig`, flat final native libraries, native library resources, output JAR entries and workspace reports. `native/zig-cache/**` is excluded only because it is byte-identical duplicate toolchain cache; final native libraries remain mandatory. Artifact audit is a finalization gate: if it fails after output packaging, j2ll must delete or avoid retaining the final JAR, write `reports/failure-report.json` with `stage=ARTIFACT_AUDIT`, `reasonCode=ARTIFACT_AUDIT_FAILED`, and leave readiness `finalArtifactWritten=false`.

`reports/skipped-method-report.json`

Every selected Code-bearing method that became `skipped`, including selector, class, method, descriptor, skip stage, reason code and human-readable reason. Selector matches without Code stay in selector eligibility evidence and do not trigger the skipped-method confirmation gate.

`reports/field-internalization-report.json`

Required readiness evidence even when the feature is disabled or has no candidate. It records `schemaVersion`, `reportVersion`, effective `enabled`, hybrid mutable/compile-time storage policy, primitive/reference/constant/cache/global-reference/lifecycle policies, and stable decisions. Each decision uses a hash-only `fieldIdHash`, status `INTERNALIZED` or `KEPT`, `internalizationStorage`, exact `storageKind`, storage location, optional reference-sidecar index, optional opaque `nativeSlotId`, access methods, actual final native implementation paths, `removedFromOutputClass`, and ordered reason codes. Raw field owner/name/descriptor and constant values are not written to this report.

`reports/lowering-report.json`

Requested lowering set with only `nativeLowered` and `skipped` method outcomes, plus a separate selector-level `excluded` list. Every requested method records `retentionMode` (`registeredNative`, `internalNativeOnly`, `coalescedNativeOnly`, or `javaBytecode`), `javaMethodPresent` and `registrationPresent`. `coalescedNativeOnly` also records the stable caller method key in `coalescedInto`; this is a physical-retention distinction, not another lowering outcome. Build failures are referenced as diagnostics and are not method outcomes.

`reports/opcode-support-matrix.json`

Deterministic opcode/category/status/reason/test coverage matrix used by release readiness gates. Each row includes `testCoverage`, `coverageLevel` (`unit`, `integration`, `childJvmE2e`, or `releaseSuite`) and `evidenceCount`. It covers supported direct lowering, helper-backed opcodes and precise skipped boundaries such as legacy subroutines/finally shapes.

`reports/packaging-report.json`

Manifest/resource/signature handling, the generated runtime loader, native registration summary and output jar validation result. Each rewritten-method entry records `javaMethodPresent` and `registrationPresent`; an `internalNativeOnly` entry has both false and is absent from registered-native groups. Each built target also records the strict final-binary unwind section inspection as `unwindSections` (section name to byte size); the Zig manifest separately records `generatedCUnwindInfoRetained`, `llvmUnwindModuleCount`, `llvmUnwindOmittedModuleCount`, `llvmUnwindRetainedModuleCount`, `unmodeledObjectInputCount`, `finalUnwindOmissionExpected` and the effective retention reason. When method-table hiding is enabled, the report also records hash/token-only `methodTableHiding` evidence: enabled/status, opaque plan id, owner/binding counts, owner hashes and report-only binding tokens, plus `physicalStrategy=ownerLocalTransientStraightLine`, `runtimeTokenTableEmitted=false`, `runtimeFunctionTableEmitted=false` and temporary-table zeroization evidence. It does not write raw owner/member mapping in that object.

`reports/protection-report.json`

Protection passes that ran, hash-only seed identity and per-method skipped-pass reasons. Root `coverage` records stable hash-only per-subject facts and aggregates for `requested`, `applicability` (`applicable`, `notApplicable`, or `unknown`), `affected`, `status` and `reasonCode`. IR per-method passes provide explicit applicability; producer paths that have not persisted it must write `unknown`, never infer it from `SKIPPED`. Such unknown facts use `affected=false` until the producer supplies direct evidence. Reports may include root and per-pass `sensitivePlaintextFacts`; each fact records `literalHash`, `sourceMethod`, `passName`, `pathKind`, `gateMode`, `sourceSurface`, `reason`, `promotionReason` and `artifactSurfaces`, never the original plaintext. The pipeline may keep plaintext in memory long enough to feed artifact audit, but report JSON remains hash-only.

`reports/support-matrix.json`

Deterministic feature/status/reason/test coverage matrix for Java/JVM support tiers, native/runtime-helper support, skipped boundaries, signing, managed Zig build and packaging behavior. Each row includes `testCoverage`, machine-readable `coverageLevel` and `evidenceCount`. Supported exception evidence distinguishes unprotected pending-exception propagation and protected ordered JNI dispatch from remaining exception-state/frame boundaries; initializer evidence distinguishes constructor verifier-prefix/post-init splitting from unsupported pre-init shapes; `Object.getClass()` and `Thread.sleep(J)V` have explicit env/JVM-backed helper rows rather than generic unresolved JDK dispatch.

`reports/known-blockers.json`

Known release blockers that remain intentionally conservative. Each row has stable id, reason code, severity, target milestone, current behavior, report location and suggested future path. `severity` uses `beta-blocker`, `rc-blocker`, `future-blocker` or `non-goal`; `targetMilestone` uses values such as `beta`, `rc`, `post-rc` or `explicit-nongoal`. Explicit non-goals record JVM-hosted boundaries such as no standalone/native-image output and no native object model/GC/thread scheduler.

`reports/summary.json`

User-readable machine-parseable summary report written for build, dry-run and config-failure CLI workspaces. It aggregates final status, final artifact state, output JAR path, diagnostics counts/top errors, method status counts, native target status/resource/SHA summary, protection/audit counts, artifact-audit status, readiness status/top missing evidence and top blocker ids. It is derived from existing reports and does not include sensitive plaintext or raw protection seeds.

`reports/summary.md`

Diff-stable human summary derived from `reports/summary.json`. It lists final status, final artifact state, output JAR path, diagnostics counts, method status counts, native target buildable/unbuildable summary and gate status without copying raw protection seeds, sensitive plaintext or local workspace paths.

`reports/index.json`

Stable report manifest for the workspace. It lists every generated `.json` / `.md` report except itself, plus `config.resolved.json` and `intermediates/intermediates-manifest.json` when present. Each entry includes `path`, `reportVersion`, `sha256`, `requiredForReadiness`, `requiredForBeta`, `requiredForRc`, `producedOnFailure` and coarse `status`. `field-internalization-report.json` is required readiness/beta/RC/failure evidence, including when the pass is disabled. This workspace index is the sole report manifest and authoritative hash source; it is never copied into the final application JAR. Readiness validates required report existence/hash and separately verifies that the final JAR omits all private j2ll metadata.

`reports/release-readiness.json`

Release readiness gate result. The gate validates that required reports exist and that artifact audit, packaging, symbol audit, support matrix, opcode matrix and known blockers contain their contract fields. A failed gate is a report/preflight signal, not a standalone runtime mode. Schema v1 currently includes v3 readiness evidence fields:

- `missingEvidence`: machine-readable failed-evidence summary with `type`, `name`, `reasonCode`, `detail` and `reportPath`. Types include `missingReport`, `missingBlockerEvidence`, `missingSuiteCategory`, `artifactAuditNotPassed`, `metadataConsistencyMissing`, `blockingSensitivePlaintextLeak`, `determinismMissing`, `targetEvidenceIncomplete` and `failedCheck`.
- `suiteCoverageByBlocker`: one entry per known blocker with blocker id, reason code, report location, coverage state, evidence type (`releaseSuiteCase`, `weirdBytecodeSeed`, or `missing`), case name when applicable and expected status.
- `blockerEvidenceComplete`: true only when every known blocker has release suite or explicit seed evidence in strict suite mode.
- `targetEvidenceComplete`: true when every selected target artifact entry records required/buildable state, Zig triple, expected library path/name, reason, capability, SDK requirement, failure kind, build log tail and correct actual-artifact nullability.
- `finalArtifactWritten`: true only when the final output JAR exists. A failed required target must leave this false.
- `determinismEvidenceComplete`: true when strict suite summary includes stable case/report ordering and determinism evidence.
- `metadataConsistencyPassed`: true only when artifact audit reports that final-JAR private j2ll metadata is absent and workspace embedded-library evidence matches target artifacts, including the pure-hash logical library-name contract.
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
- `standard`: regular native-helper/skipped-boundary/protection regression evidence.
- `beta`: user-facing usability evidence. Requires CLI jar smoke, docs examples validation, report index evidence, minimal LLVM native evidence and mixed helper/skipped-boundary evidence. `beta-blocker` rows must be covered by suite evidence or accepted workaround evidence; otherwise `betaProfilePassed=false`. Future or explicit non-goal blockers remain visible but do not block beta when they have evidence/future path.
- `rc`: release-candidate evidence. Requires all RC categories, blocker evidence, determinism, signing/packaging preservation, artifact audit failure evidence and injected/actual required-target failure hygiene evidence. Generic real six-target toolchain artifacts are verified by `ZigCrossTargetBuildTest`; protection-specific shared-source/build-graph/content/privacy/export evidence is verified by `ProtectionCrossTargetEvidenceTest`.

Sample project docs live under `docs/samples/`, currently `basic-cli-app.md` and `reflection-service-app.md`. They include source snippets, config shape, commands, expected output and report highlights, and are tested so they do not drift away from `docs/examples/*.json`.

Release suite summary written by the deterministic test harness, not by ordinary CLI pipeline runs. Strict readiness mode requires this file for suite workspaces. It records `schemaVersion`, `reportVersion`, `suiteName`, `profile` (`smoke`, `standard`, `beta` or `rc`), `requiredCategories`, `missingCategories`, stable `cases` ordering, `aggregate` (`totalCases`, `successCases`, `expectedFailureCases`, `casesByCategory`, `casesByFeature`, `strictEvidenceComplete`, `determinismEvidenceComplete`), root `determinismEvidenceComplete`, each case `name`, `category`, `features`, expected support statuses, original/output child JVM exit/stdout/stderr when child JVM differential is applicable, collected produced report paths, diagnostics, protection setting, signature policy and whether pipeline success was expected. Expected config/toolchain/artifact failures may omit original/output child JVM runs, but must record `expectedFailure=true`, `expectedFailureStage`, `expectedFailureReasonCode`, `finalArtifactWritten=false`, a matching diagnostic and `failure-report.json`. Beta profile strict readiness requires CLI artifact smoke, docs example validation and report-index evidence; RC profile strict readiness requires `missingCategories` to be empty.

Strict readiness gate v6 treats `expectedSupportStatuses` and `expectedSupportEvidence` as release blocker coverage evidence. `beta-blocker` and `rc-blocker` known-blocker reasons must be covered either by a suite case expected status/diagnostic or by a documented weird-bytecode seed reason. `future-blocker` and explicit `non-goal` rows remain visible in coverage output but do not block RC strict readiness. Expected failure cases, such as invalid config, signed input rejected by `signaturePolicy: "fail"`, artifact audit failure or an injected/actual required-target capability, compile or link failure with `ZIG_TARGET_UNBUILDABLE`, must have `output: null`, `finalArtifactWritten=false`, `failure-report.json` and a matching diagnostic/stage/reason. Non-host selection alone is a successful structural cross-build case, not expected-failure evidence. Successful runtime cases include output child JVM results, passed artifact audit and the required report set; structural-only cross-target cases include target-format/export evidence and clearly separate pending non-host runtime E2E.

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
      "code": "UNSUPPORTED_EXCEPTION_STATE_MERGE",
      "stage": "LOWERING",
      "class": "pkg/Foo",
      "method": "run",
      "descriptor": "()V",
      "instructionOffset": 12,
      "artifactId": "pkg/Foo#run!()V",
      "message": "throw-site local state cannot be merged into the handler block arguments",
      "decision": "skipped"
    }
  ]
}
```

Required diagnostic fields:

- `severity`: `info`, `warning` or `error`.
- `code`: stable machine-readable diagnostic code.
- `stage`: one of the stage enum values in `docs/pipeline/08-diagnostics-validation-testing.md`.
- `message`: human-readable message.
- `decision`: nullable; when present for a method, one of `nativeLowered`, `skipped` or `excluded`; build-level diagnostics may use `warning` or leave it null.

Location fields are nullable only when the diagnostic is not tied to a method or instruction:

- `class`
- `method`
- `descriptor`
- `instructionOffset`
- `artifactId`

`reports/skipped-method-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "reportVersion": 1,
  "confirmationRequired": true,
  "confirmationDecision": "approved",
  "entries": [
    {
      "selector": "pkg/Foo#bad!()V",
      "class": "pkg/Foo",
      "method": "bad",
      "descriptor": "()V",
      "status": "skipped",
      "hasCode": true,
      "stage": "LOWERING",
      "reasonCode": "UNSUPPORTED_OPCODE",
      "reason": "jsr/ret is outside the current compiler capability boundary",
      "affectsCallers": true
    }
  ]
}
```

`confirmationDecision` is one of `notAnalyzed`, `notRequired`, `approved`, `rejected`, `inputError`, or `notEvaluatedPriorFailure`. The public programmatic pipeline overloads that do not receive a `SkippedMethodApproval` fail closed with the equivalent of `rejected`; an embedding application must pass an explicit approval callback if retaining skipped Java bodies is acceptable.

The method list and confirmation decision form one invocation-scoped evidence record. If Zig or a later native/toolchain step fails after the gate, the failure report must preserve that same record (`approved` with the listed methods, or `notRequired` with an empty list); it must not overwrite it with `notAnalyzed`.

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
      "status": "nativeLowered",
      "rewriteStrategy": "nativeOriginal",
      "retentionMode": "registeredNative",
      "javaMethodPresent": true,
      "registrationPresent": true,
      "accessFlags": ["public"],
      "compilerFlags": ["synthetic"],
      "nativeSymbol": "j2ll_pkg_Foo_run_8f3a21c0d4e5f607",
      "registrationOwner": "pkg/Foo",
      "nativeImplementationPath": "LLVM_NATIVE_PATH",
      "helperBackedSites": [
        {
          "helperKind": "j2ll_rt_string_builder_append_ref",
          "helperIdentityHash": "70a8c742dd9a6a6a548c10d6e092e1e8e44f84367d59d4a08e1b8edff57d25d1",
          "reasonCode": "HELPER_BACKED_LOWERING"
        },
        {
          "helperKind": "field",
          "helperIdentityHash": "696c917c9a08646f81f6890f1f8b257ac4940b0b657c6bc69f5996f3555028a2",
          "reasonCode": "FIELD_HELPER"
        },
        {
          "helperKind": "direct",
          "helperIdentityHash": "3d4edb6f8043c09715c842f0dcb20f7965a06ba855da81873aa8b6eb604f4c50",
          "reasonCode": "DIRECT_LLVM_CALL"
        }
      ]
    }
  ],
  "skippedMethods": [
    {
      "selector": "pkg/Foo#bad!()V",
      "class": "pkg/Foo",
      "method": "bad",
      "descriptor": "()V",
      "status": "skipped",
      "stage": "LOWERING",
      "reasonCode": "UNSUPPORTED_OPCODE",
      "reason": "jsr/ret is outside the current compiler capability boundary",
      "nativeSymbol": null,
      "registrationOwner": null,
      "nativeImplementationPath": null
    }
  ]
}
```

`accessFlags` records JVM access facts. `compilerFlags` records audit-oriented flags such as `bridge`, `synthetic`, `enumGenerated` and `recordGenerated`; these flags do not imply skip. `retentionMode`, optional `coalescedInto`, `javaMethodPresent` and `registrationPresent` separate ordinary registered natives, standalone/coalesced internal-only natives, and preserved Java bytecode without adding another method outcome.
`nativeImplementationPath` records whether the registered native body is `LLVM_NATIVE_PATH`, `TEMPLATE_JNI_PATH`, or `null` when no executable native body was produced for that requested method.
`helperBackedSites` records metadata/reflection/JNI/Unsafe/MethodHandle/ConstantDynamic sites whose semantics are executed from a native implementation through a runtime helper or ordinary JVM/JNI dispatch rather than direct LLVM instructions. `helperKind` is a non-sensitive category/base symbol and `helperIdentityHash` is a domain-separated SHA-256 of the complete compiler-private helper identity. The full helper string is never serialized because it may contain owner/member descriptors or a `string:<business literal>` carrier. This remains true native lowering and does not imply an embedded copy of the original method body. It also records field/array/arraycopy/allocation/String/StringBuilder/JDK/div-rem/JVM-numeric/monitor/exception/call/stub decisions such as `FIELD_HELPER`, `ARRAY_HELPER`, `ARRAYCOPY_HELPER`, `ALLOCATION_HELPER`, `STRING_HELPER`, `STRING_BUILDER_HELPER`, `JDK_INTRINSIC_HELPER`, `JDK_COLLECTION_HELPER`, `THROWABLE_HELPER`, `THREAD_HELPER`, `JVM_NUMERIC_HELPER`, `DIV_REM_EXCEPTION_HELPER`, `MONITOR_HELPER`, `SYNCHRONIZED_METHOD_HELPER`, `EXCEPTION_HELPER`, `REFLECTION_HELPER`, `REFLECTION_FIELD_HELPER`, `REFLECTION_METHOD_HELPER`, `REFLECTION_CONSTRUCTOR_HELPER`, `REFLECTION_ACCESSIBLE_HELPER`, `UNSAFE_HELPER`, `DIRECT_LLVM_CALL`, `JVM_CALL_HELPER`, `DISPATCH_HELPER`, `DEFAULT_INTERFACE_DISPATCH_HELPER`, `DEFERRED_DISPATCH_HELPER`, `CONSTRUCTOR_BODY_HELPER`, `CLASS_INITIALIZER_BODY_HELPER`, `JNI_ABI_REGISTER_NATIVES` and `RUNTIME_METADATA_HELPER`. Current static reflection helper coverage includes no-arg, reference, primitive and array constant-parameter method/constructor descriptors, typed field accessors `getInt/setInt/getBoolean/setBoolean/getLong/setLong/getDouble/setDouble`, reference `Field.get/set`, and a bounded `setAccessible(true)` helper for statically resolved Method/Constructor/Field objects. Dynamic reflection strings, dynamic parameter arrays, scan-style reflection and MethodHandle adapter chains may use ordinary JVM/JNI dispatch when the descriptor fits the validated bridge matrix. A native call into a Java/JDK target is helper-backed execution, not an embedded bytecode copy of the caller. If an operation cannot be represented by direct LLVM or an approved helper/dispatch bridge, the whole selected caller is `skipped` with a stable reason such as `REFLECTION_UNSUPPORTED_SCAN`, `UNSAFE_RAW_MEMORY_UNSUPPORTED`, `ALT_METAFACTORY_UNSUPPORTED`, `UNSUPPORTED_JVM_EXCEPTION_FLOW`, `UNSUPPORTED_EXCEPTION_STATE_MERGE` or `UNSUPPORTED_DEFAULT_INTERFACE_SUPER`; the evidence belongs in `reports/skipped-method-report.json`, and no embedded class blob is allowed.

Runtime metadata and raw/optimized SSA dumps are diagnostic sidecars when
explicitly enabled by intermediates/`--debug`. They may contain source-level
class/member identities and pre-string-protection business literals required to
debug compiler semantics. They are not publication artifacts and are not
embedded in the output JAR. Generated C, LLVM, final native libraries and all
primary reports remain subject to the release plaintext contract. Runtime
metadata dumps may include a `reflectionReachability` section with resolved
class/method/field targets and unsupported/skipped reflection sites; lowering
status remains governed by `reports/lowering-report.json`.

`reports/packaging-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "outputJar": "input.jar",
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
  "generatedLoaders": ["native0/Loader"],
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
    },
    {
      "target": "macos-arm64",
      "jarPath": "native0/arm64-macos.dylib",
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
    "buildableTargets": ["linux-x64", "macos-arm64"],
    "skippedTargets": [],
    "failedTargets": []
  }
}
```

`reports/protection-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "reportVersion": 2,
  "seedMode": "randomized",
  "seedHash": "context-bound-build-identity-hash",
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

`reports/field-internalization-report.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "reportVersion": 1,
  "enabled": true,
  "storagePolicy": "descriptorAwareHybrid",
  "primitiveStoragePolicy": "perDefiningJclassWeakIdentityAtomicBits",
  "referenceStoragePolicy": "jvmClassValueObjectArray",
  "constantStoragePolicy": "ssaFoldedNoRuntimeStorage",
  "atomicPolicy": "relaxedAtomicPrimitiveBits",
  "cachePolicy": "jvmClassValuePerDefiningClass+lazyPerNativeFunctionActivationLocalRef",
  "globalReferencePolicy": "noStrongNativeGlobalRefs",
  "lifecyclePolicy": "primitiveJweakLazyCleanup+referenceClassValueLifecycle",
  "unloadAware": true,
  "decisions": [
    {
      "fieldIdHash": "sha256-of-owner-name-descriptor",
      "status": "INTERNALIZED",
      "internalizationStorage": "NATIVE_SLOT",
      "storageKind": "REFERENCE",
      "storageLocation": "jvmClassValueSidecar",
      "referenceSidecarIndex": 0,
      "nativeSlotId": "j2ll_nfs_<opaque-token>",
      "accessMethods": ["pkg/Foo#get!()Ljava/lang/Object;", "pkg/Foo#set!(Ljava/lang/Object;)V"],
      "finalImplementationPaths": ["LLVM_NATIVE_PATH"],
      "removedFromOutputClass": true,
      "reasonCodes": ["FIELD_INTERNALIZATION_ELIGIBLE"]
    }
  ]
}
```

Pass `status` values are `RAN`, `SKIPPED` and `FAILED`. Every pass result records a stable `reasonCode`; examples include `OK`, `METHOD_INLINING`, `METHOD_INLINING_NO_CANDIDATE`, `METHOD_SPLITTING`, `METHOD_SPLITTING_NO_SAFE_REGION`, `IR_CALL_INDIRECTION_TABLE`, `IR_CALL_INDIRECTION_BACKEND_NO_CANDIDATE`, `FIELD_INTERNALIZATION`, `FIELD_INTERNALIZATION_NO_CANDIDATE`, `METHOD_TABLE_HIDING_TRANSIENT_OWNER_LAYOUT`, `LLVM_OPAQUE_PREDICATES`, `LLVM_OPAQUE_PREDICATES_NO_CANDIDATE`, `LLVM_BLOCK_LAYOUT_PERTURBATION`, `LLVM_BLOCK_LAYOUT_NO_CANDIDATE`, `LLVM_GLOBAL_LAYOUT`, `LLVM_GLOBAL_LAYOUT_NO_CANDIDATE`, `CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE`, `CALL_INDIRECTION_TABLE`, `CALL_INDIRECTION_DISPATCHER`, `PROTECTION_PASS_DISABLED`, `NO_STRING_CONSTANT_CARRIER`, `PROTECTION_STUB_BACKED_METHOD` and `PROTECTION_MONITOR_SENSITIVE_SKIP`. Disabled pass and method/module inapplicability both use `SKIPPED`; neither changes method lowering status. Validator/final-plan failure uses `FAILED` and prevents an invalid transformed artifact from continuing.

`reports/symbol-audit.json` minimum shape:

```json
{
  "schemaVersion": 1,
  "libraries": [
    {
      "target": "linux-x64",
      "path": "native/x64-linux.so",
      "allowedExports": ["JNI_OnLoad"],
      "actualExports": ["JNI_OnLoad"],
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
  Loader.class
  x64-windows.dll
  arm64-windows.dll
  x64-linux.so
  arm64-linux.so
  x64-macos.dylib
  arm64-macos.dylib
```

`Loader.class` always appears exactly once. Only selected native targets appear. For example, if `linuxX64` and `macosArm64` are true, these generated entries are required:

```text
<embeddedLibraryDirectory>/Loader.class
<embeddedLibraryDirectory>/x64-linux.so
<embeddedLibraryDirectory>/arm64-macos.dylib
```

The generated class is a Java 17 classfile whose internal name is exactly `<embeddedLibraryDirectory>/Loader`. It always contains only the required native target selection, SHA-256 verification, extraction/loading and registration support. It never contains `defineHiddenFallback` or any class-definition/blob-decoding API. If the final field-internalization plan contains references/arrays, the same physical Loader directly extends `ClassValue` and conditionally owns the private per-defining-Class `Object[]` sidecar accessor; otherwise that sidecar support is absent. No companion/nested class, `J2llFallbackSupport.class`, `J2llNativeLoaderSupport.class`, or legacy `j2ll/generated/<artifact-id>/NativeLoader.class` entry is emitted. j2ll uses this loader plus `RegisterNatives`; Java method implementation functions remain internal/hidden and are not exported as JNI method-name symbols.

### Method Rewrite Strategies

Packaging rewrites methods according to their JVM method kind. The strategy is recorded in `reports/lowering-report.json` and `reports/packaging-report.json`.

`nativeOriginal`

普通 class method with Code, excluding `<init>` and `<clinit>`.

- The original method keeps its original name, descriptor and user-visible access flags.
- The Code attribute is removed and the method is marked `ACC_NATIVE`.
- The generated native implementation is bound with `RegisterNatives`.
- The native implementation symbol is internal/hidden; it is not exported as a JNI name-mangled method symbol.

`internalNativeOnly`

Final-plan-only rewrite for a method already proven `nativeLowered` through `LLVM_NATIVE_PATH`.

- Packaging removes the complete Java `method_info`; it does not leave an `ACC_NATIVE` declaration or Java stub.
- The method is absent from `RegisterNatives`; its hidden LLVM implementation remains linked because validated native callers reference it.
- Protected static callers may cross owner through a defining-class/nested-local-frame native bridge. Protected/private instance methods are limited to same-owner exact dispatch.
- Any plan/declaration mismatch or residual invocation/handle/bootstrap/enclosing-method reference fails artifact finalization instead of silently restoring the Java method.

`constructorStub`

Java `<init>` cannot become an ordinary native method. j2ll must keep a legal constructor stub.

- The original `<init>` remains a non-native constructor with Code.
- The stub preserves the exact verifier-required prefix from method entry through the unique invocation that initializes `uninitializedThis`: `this(...)` or `super(...)`, with its original owner, descriptor and argument-computation bytecode.
- After the object is initialized, the stub calls a private generated native body helper whose name is a build-scoped hash-only Java identifier. The 128-bit hash is reversibly encoded nibble-by-nibble with the letters `a` through `p`, so every character is a legal Java identifier character without adding a fixed prefix. The name has no stable product, constructor, or body prefix and changes with the build identity.
- The supported split requires a unique, linear prefix, and the current implementation requires the whole constructor to have no exception table; branches before initialization or ambiguous initializing calls fail closed. The post-init IR must independently pass the complete LLVM/helper support matrix.
- The immutable initializer plan is shared by native compilation, rewrite, registration and audit. Packaging must not reconstruct a different split.
- If an input method already occupies the generated same-owner carrier name and descriptor, the build fails before Zig with `GENERATED_INITIALIZER_HELPER_COLLISION`; the rewriter never silently binds the stub to that source method.
- If the constructor cannot be split while preserving verifier semantics, or the post-init native body is incomplete, the entire constructor is `skipped` and retains its original Code.

`classInitializerStub`

Java `<clinit>` is invoked implicitly by the JVM and is not a normal native method target. j2ll must keep or create a legal class initializer stub.

- If a class has native-lowered methods and no `<clinit>`, j2ll may generate one.
- The stub first calls the generated loader to load the native library and register owner-class native methods.
- If the original `<clinit>` has Code and its complete IR passes the final LLVM/helper support matrix, its body is lowered into a private generated static native helper with an independently domain-separated, build-scoped hash-only Java identifier.
- The stub calls the native helper after loader initialization.
- The same exact name-and-descriptor collision rule applies to the class-initializer carrier and fails closed with `GENERATED_INITIALIZER_HELPER_COLLISION`.
- The loader and registration path must handle recursive class initialization and classloader concurrency.

`interfaceMethodStub`

Interface methods cannot be marked native, but Java 8+ interface default, static and private methods may have Code.

- The original interface method remains a legal Java method with Code.
- The stub calls the generated loader and then a generated class helper that owns the actual native method.
- For default interface methods, the helper receives `this` explicitly.
- Abstract interface methods, annotation elements and interface declarations without Code remain outside the Code-bearing requested set and are recorded only in selector eligibility evidence.

`skipped`

No rewrite or registration is attempted when a selected Code-bearing method has an unsupported body. The method retains its original Code, is recorded in `reports/skipped-method-report.json`, and participates in the pre-Zig confirmation gate.

`nonCodeSelectorMatch`

Abstract methods, already-native methods, interface declarations without Code and annotation elements have no Java method body to lower. A selector match is recorded for audit, but the declaration does not enter the requested set, receives no method status and does not trigger confirmation.

### Member Declaration Rewrite

Method rewrite strategy and field internalization are separate plans. When `fieldInternalization` approves a field:

- every approved access has already been rewritten to an opaque native slot and revalidated against the final `NativeImplementationPlan`;
- packaging removes exactly the approved `FieldNode` only if its access flags, descriptor and metadata still satisfy the plan;
- any plan/declaration mismatch is `FIELD_INTERNALIZATION_REWRITE_FAILED`, fails closed, and prevents a final JAR;
- the packaged JAR is scanned again for residual declaration, field instruction, field MethodHandle and bootstrap field reference before artifact audit can pass.

No other field, method or class attribute is removed by this transform. A `KEPT` decision is an exact no-op for that field.

Method internalization consumes its own immutable final plan after ordinary method rewriting. It removes only exact `INTERNAL_NATIVE_ONLY` `MethodNode` matches. It never deletes a caller, constructor/stub helper, skipped body, annotation element or unrelated overload.

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
- `reports/packaging-report.json` also records `zigToolchain.targetArtifacts`, including selected/required target, current-host/buildable state, OS/arch classifier, library extension, exact Zig target query, expected artifact path/name/resource path, loader extraction path policy, symbol visibility policy, actual artifact SHA-256 and exported symbols for every built target, required capability, platform SDK requirement, failure kind, build log tail and Windows PDB exclusion policy.
- `reports/support-matrix.json` is a stable release-readiness artifact listing feature support (`LLVM_NATIVE_PATH`, `HELPER_BACKED`, `SKIPPED`), reason code and test coverage pointer. These feature rows do not introduce additional method status values.
- `reports/opcode-support-matrix.json` is the matching opcode-level release-readiness artifact listing opcode bucket, category, status, reason code and test coverage pointer.
- `reports/known-blockers.json` tracks remaining conservative boundaries with stable blocker id, reason code, severity, target milestone, report location and suggested future path.
- `reports/release-readiness.json` records the gate checks over required reports and their required top-level fields plus readiness fields `suiteCoverageByBlocker`, `blockerEvidenceComplete`, `targetEvidenceComplete`, `finalArtifactWritten`, `determinismEvidenceComplete`, `metadataConsistencyPassed`, `blockingSensitiveFactsPassed`, `targetPackagePlanComplete` and `strictModePassed`.
- `reports/release-suite-summary.json` is emitted by release suite tests and is required only by strict suite readiness mode. It records suite/case metadata, expected support statuses, expected support evidence with report locations, child JVM differential results and collected report paths. In strict v3, known blocker reasons must be covered by suite expected statuses/diagnostics or by weird-bytecode seed coverage, and expected failure cases must not produce output runs.

## Runtime, World, Loader, And Signature Policy

本节定义 runtime helper、unsupported-method boundary、world model 和 native registration 的正式契约。

### Runtime Helpers

Runtime helper 是随 native library 一起编译进去的 j2ll 小运行时。它不代表“放弃 native lowering”，而是让 `nativeLowered` code 能正确执行 JVM 语义。

典型 runtime helper：

- null check、array bounds check、checkcast、instanceof。
- object/array allocation、class initialization、static field access guard。
- `Object.getClass()`：null receiver通过JVM exception helper产生`NullPointerException`，非null receiver通过JNI `GetObjectClass`返回JVM-managed `Class` reference。
- tokenized field get/put helper, allocation helper, String helper and helper-backed call dispatch. Current dispatch helper subset uses JNI `GetObjectClass` / `GetMethodID` / `Call<Type>Method` for no-arg int, int-arg int, reference return and single-reference-argument/reference-return virtual/interface calls; it is not a native vtable/object-layout mechanism.
- exception create/throw/catch bridge。受保护helper site使用pending-exception读取、clear、ordered typed/catch-all dispatch和unmatched rethrow helpers；exception edge同时携带throwable与throw-site live locals。
- monitor enter/exit 和 synchronized 相关状态维护。
- string/constant decrypt helper、protection dispatch helper、method table helper。
- JNI local/global reference lifetime helper。

它的特点：

- 调用方仍然是 `nativeLowered` method。
- helper 可以用 LLVM/C 实现，也可以通过 JNI 调 JVM API。
- Java reference values in helper ABI are JVM objects/JNI references. Helpers must allocate Java-visible objects through JVM/JNI APIs, not native heap or native stack storage. Current allocation helpers use class identity tokens and JNI APIs such as `AllocObject`, `NewIntArray` and `NewObjectArray`; token metadata is sidecar/report data, not a native object layout.
- helper ABI 必须由 backend declaration、runtime stub generator 和 tests 共同约束。
- lowering report 的 `helperBackedSites` 记录这些 helper-backed native call/operation。

Ordinary JVM/JNI dispatch from a native implementation is also a supported helper-backed path. For example, a native-lowered caller may invoke an external JDK method or a `skipped` Java method through a validated JNI dispatch helper. This does not copy or embed the caller's original bytecode and therefore remains `nativeLowered`.

Protected exception dispatch and initializer splitting currently have focused evidence plus Windows real-Zig host child-JVM differential. `Object.getClass()` and `Thread.sleep(J)V` currently have focused planner/LLVM/C ABI evidence. The six-target matrix verifies structural cross-build artifacts; it is not non-host JVM runtime evidence.

### Unsupported-Method Boundary

If any operation requires re-executing the selected caller's original bytecode, or cannot be represented by direct LLVM, a generated native/template implementation, or an approved runtime/JNI helper, j2ll skips the entire method:

- keep the original method and Code attribute unchanged;
- emit no native body, wrapper, registration binding or embedded bytecode copy for it;
- record `status=skipped`, stage, reason code and human-readable reason;
- include it in the pre-Zig skipped-method confirmation gate.

The runtime and output JAR must contain no fallback helper class, hidden-class definition path, fallback blob carrier, fallback manifest or original-bytecode decoder.

### World Model

World model 是分析阶段对“程序类世界是否完整”的假设。它会影响 CHA/RTA、devirtualization、IR call indirection、field internalization 和 method internalization。当前 `methodTableHiding` 只隐藏已经确定的 native registration mapping，不改变 Java dispatch，因此本身不要求 `CLOSED_WORLD`。

常见模型：

- `CLOSED_WORLD`：历史 wire name，表示用户声明输入 JAR 与提供的 `classPath` 覆盖分析所需 JVM classes，并接受报告中明确列出的无法穷举external observer/dynamic-loading风险。它允许更激进 devirtualization，并直接满足 field/method internalization 的 world requirement；输出仍是 JVM-hosted JAR。
- `PARTIAL_WORLD`：应用 class 大体已知，但外部库或运行时可能不完整。分析必须对 external type 保守。
- `JDK_EXTERNAL_WORLD`：应用 class 可分析，JDK class 主要作为外部 runtime/library 处理。
- `UNKNOWN_DYNAMIC_WORLD`：允许 reflection、custom classloader、runtime generated class 改变类型世界。只能做非常保守的 dispatch 优化。

Declared `CLOSED_WORLD`下的RTA以冻结的保守entry plan联合求解reachable methods与reachable
allocation types。entry plan包括selected Code-bearing methods、non-private Code methods、
`<clinit>`、closed-catalog JVM/JDK callbacks和exact reflection targets；unsupported reflection
使其回退为全部Code methods。不可达method里的allocation不参与收窄，instance entry和
reference参数会seed complete hierarchy中的具体receiver候选。normal build的
`lowering-report.json.callAnalysis`必须包含world/RTA/fixed-point汇总、entry/reachable methods，
以及每个exact bytecode call-site的instruction index、declared/resolved/direct target与reason。
该证据来自同一`ProgramCallGraphAnalysis`，不得从LLVM/backend或target-count事后重建。

`worldModel` 是 required config field，推荐值为 `PARTIAL_WORLD`。需要 whole-program scope 的功能统一通过 execution requirement 描述。当前 field/method internalization 在 build 中分别允许用户显式 Y 接受 current-JAR-only 边界；method decision覆盖private/protected及exact allowlisted public static，public instance仍只接受declared `CLOSED_WORLD`。两个决定独立、按稳定顺序询问。其他未实现降级的 requirement 仍应 fail closed。任何批准都必须 feature-scoped、仅本次 invocation 有效并进入 diagnostics/report，不能把配置改写为 `CLOSED_WORLD`。

### Loader And Native Registration

loader/native registration 需要解决三件事：

- 从 output jar 中按 OS/arch 选择并加载对应 dynamic library。
- 确保 `nativeLowered` Java methods 在第一次调用前绑定到 native implementation。
- 在 binary hardening 下只导出必要 ABI，隐藏 Java method internal LLVM functions。

正式方案：

- 使用唯一的 `<embeddedLibraryDirectory>/Loader.class` + `RegisterNatives`。该 classfile 固定为 Java 17，internal name 固定为 `<embeddedLibraryDirectory>/Loader`。
- Loader 始终包含 native loading/registration，且永不包含 `defineHiddenFallback`、bytecode decoder 或 class-definition API。按需的 `ClassValue<Object[]>` sidecar 是唯一可选功能。不再输出 `J2llFallbackSupport.class`、`J2llNativeLoaderSupport.class` 或 `j2ll/generated/<artifact-id>/NativeLoader.class`。
- Rewritten owner classes call this loader from `<clinit>` or from a generated method stub before the first native helper call. If an existing `<clinit>` exists, loader initialization is prepended before native-lowered method use.
- Dynamic libraries are extracted from jar resources to a per-classloader, content-addressed temp/cache path under `java.io.tmpdir`.
- Extracted libraries must be verified against SHA-256 metadata before `System.load`.
- Native registration tables are grouped per owner class.
- `JNI_OnLoad` only uses `FindClass` for the unique generated Loader anchor. It obtains that class's exact defining `ClassLoader`, then resolves every business registration owner with `Class.forName(binaryName, false, definingLoader)`. Business owners are therefore loaded/linked without running `<clinit>` before their native helpers have been registered. TCCL and system-loader fallback are forbidden.
- Interface method native helpers are registered against generated helper classes, not against the interface method itself.
- `<init>` and `<clinit>` body helpers are registered as generated private static native helper methods on the owner class, not as native constructors or native class initializers.
- Loader state is per classloader and thread-safe, using fail-closed `UNLOADED`, `LOADING`, `READY`, and `FAILED` states. It enters `LOADING` before `System.load`, reaches `READY` only after `JNI_OnLoad` has completed, rejects same-thread reentry before a second load, and never retries after `FAILED`.
- Extraction paths must not be user-controlled relative paths; temp files should use restrictive permissions where the platform supports them.
- The generated libraries export only the JVM-required `JNI_OnLoad` root, apart from platform-inherent runtime symbols explicitly tolerated by the target audit. For non-empty registration, that root selects one of two activation-local paths through exactly three static hash-only routes (`R0 -> aggregate` or `R1 -> R2 -> aggregate`); every path enters the same aggregate exactly once. The routes, aggregate, forward chunks and per-owner helpers remain static/internal, carry the generated-C noinline/disable-tail policy, and preserve each planned direct call with a post-call volatile continuation. Zero-owner output generates no routes or chunks. Java method implementation functions, dispatchers and protection tables stay internal/hidden.
- Loader, extraction and `RegisterNatives` failures throw `UnsatisfiedLinkError` with a clear message.

The reserved base/MR loader-entry collision checks run before Zig. A base collision reports `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION`; a multi-release shadow reports `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW`. Because the loader binary name is directory-derived rather than artifact-derived, multiple different artifacts using the same `embeddedLibraryDirectory` in one defining `ClassLoader` remain an explicit known boundary. Applications that load such artifacts together should assign application-unique directories.

## Native Lowering Guarantee

For every method reported as `nativeLowered`:

- The native implementation exists in every selected target dynamic library.
- An ordinary registered native keeps a rewritten class declaration/stub and a matching registration entry.
- An `internalNativeOnly` method instead has no Java declaration and no registration entry; every approved input-visible use resolves to its retained hidden native implementation.
- The lowering report records the method as `nativeLowered`.
- Runtime/JNI helper-backed operations are permitted, but no path may execute an embedded copy of the selected method's original bytecode.

For every method reported as `skipped`:

- The original bytecode remains runnable in the output jar.
- The lowering report records the skip reason and stage.
- The native registration plan does not include that method, and no native source/blob carrier contains a copy of its method body.
- Protection pass inapplicability alone must not skip the method; it skips only that protection pass and emits a warning.
- Default build lists it and requires explicit Y before entering the Zig stage.

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
- whether the selected method status is `nativeLowered` or `skipped`, or whether the selector excluded it as `excluded`
- any display-safe escaping used in `safe-method-name`

## Runtime And Shared Artifacts

Runtime helper artifacts that are not tied to a single class go under:

```text
intermediates/runtime/
  runtime-helpers.c
  runtime-helpers.h
  helper-catalog.json
```

No fallback-blob manifest, blob carrier source or decoded class artifact is generated.

## Dynamic Library Output

Native libraries are written to workspace `native/` and embedded into the final jar.

Workspace paths:

```text
native/x64-windows.dll
native/arm64-windows.dll
native/x64-linux.so
native/arm64-linux.so
native/x64-macos.dylib
native/arm64-macos.dylib
```

Workspace dynamic libraries are intentionally flat because the fixed target filenames are unique across the six-target matrix. `native/zig-workspace/` remains reserved for generated Zig sources, manifests and logs. This workspace layout is independent of the JAR resource prefix below.

JAR paths:

```text
<embeddedLibraryDirectory>/Loader.class
<embeddedLibraryDirectory>/x64-windows.dll
<embeddedLibraryDirectory>/arm64-windows.dll
<embeddedLibraryDirectory>/x64-linux.so
<embeddedLibraryDirectory>/arm64-linux.so
<embeddedLibraryDirectory>/x64-macos.dylib
<embeddedLibraryDirectory>/arm64-macos.dylib
```

`Loader.class` is generated once regardless of target count. It remains present for every native build, always excludes fallback/class-definition methods, and adds `ClassValue<Object[]>` sidecar support only when an approved internalized reference/array field requires it.

Binary hardening rules:

- Export only JNI / C ABI wrapper symbols required by the loader/registration plan.
- Java method implementation functions are internal LLVM functions or hidden symbols.
- Internal helpers, dispatchers, method tables and protection tables are hidden unless explicitly required by runtime ABI.
- Windows release output must not package `.pdb` files.
- Cross-target audit parses the produced target format directly: PE export directory for Windows, ELF dynamic symbols for Linux and Mach-O export trie/symbol table for macOS. It also verifies target architecture and does not depend on a host-only `nm`.
- `reports/symbol-audit.json` must record the platform allowlist and actual exported symbols for every built target.

## Failure Outputs

On failure, the workspace remains for debugging. Expected files:

```text
config.resolved.json
reports/artifact-audit.json
reports/diagnostics.json
reports/failure-report.json
reports/field-internalization-report.json
reports/skipped-method-report.json
reports/known-blockers.json
reports/lowering-report.json
reports/opcode-support-matrix.json
reports/packaging-report.json
reports/protection-report.json
reports/release-readiness.json
reports/release-suite-summary.json
reports/support-matrix.json
reports/symbol-audit.json
logs/zig-build.log
intermediates/
```

`<input-jar-file-name>` at the workspace root is only written when the build succeeds.
