# Protection And Obfuscation Plan

本文档定义 rewrite 后 j2ll 的保护/混淆设计。目标是把混淆分成三个清晰层次，避免把 Java 语义、LLVM emission 和 binary packaging 混在一起。

```text
SSA IR protection passes
  -> LLVM module model protection passes
  -> binary symbol visibility / strip
```

核心原则：

- 先保证语义正确，再增加强度。
- schema v1 中每个 pass 只有开关；需要可重复输出时使用全局 protection seed。
- 每个 pass 声明输入 IR 形态、输出 IR 形态、是否保持 SSA、是否改变 CFG、是否需要 runtime helper。
- 不做脆弱的 LLVM `.ll` 文本后处理。LLVM IR 混淆必须基于 LLVM module model。
- 对外只导出 loader/bootstrap 需要的 JNI / C ABI wrapper；Java method 对应的 LLVM function 在跨 object link 时可使用 `external hidden`，但不得进入 dynamic export list。

## Layer 1: SSA IR Protection

该层发生在 validated SSA IR 之后、LLVM lowering 之前。它最适合做需要 Java 语义、类型、CFG、call graph 和 runtime analysis facts 的混淆。

推荐包：

```text
xyz.melodysky.ir.pass.protection
```

推荐 pipeline：

```text
canonicalize
  -> validate
  -> semantics-preserving optimization
  -> protection passes
  -> protection-aware validation
  -> backend preparation
```

注意：保护 pass 后不要再跑会把混淆清掉的 aggressive cleanup。只允许必要的合法性修复和 validator。

### 控制流平坦化

目标：把 method CFG 转成 dispatcher-driven 结构，降低原始控制流可读性。

建议实现：

- `ControlFlowFlatteningPass`
- `DispatcherBlockBuilder`
- `StateVariableAllocator`
- `FlatteningPlan`

边界：

- 必须保留 exception edge 语义。
- synchronized/monitor 区域内谨慎启用，第一版可禁用。
- try/catch/finally 复杂方法第一版允许跳过该 protection pass 并 warning，不强行 flatten。
- 当前 IR protection pipeline 对含 `MONITOR_ENTER` / `MONITOR_EXIT` / monitor happens-before marker 的 method 默认跳过并发出 `PROTECTION_MONITOR_SENSITIVE_SKIP` warning，避免改写 monitor-sensitive region。

测试：

- branch / loop / switch flatten 后 runtime parity。
- exception path parity。
- validator 检查 CFG 和 SSA 合法。

当前 v1 已实现保守 IR 子集：`ControlFlowFlatteningPass` 对无 exception edge、无 block parameter、无 monitor/JMM/call/field/helper-sensitive opcode、无 target arguments 的 primitive LLVM-native 多 block method 生成 dispatcher block + state switch + transition blocks。该 pass 保持 JVM-visible helper 语义不变；不支持 shape 记录 `CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE`，成功运行记录 `CONTROL_FLOW_FLATTENING`。child JVM E2E 覆盖 protected if/else / nested branch path。

### 虚假分支

目标：插入 opaque predicate 和永不执行/极少执行的 branch。

建议实现：

- `OpaquePredicatePass`
- `OpaquePredicateFactory`
- `FakeBranchInserter`

边界：

- predicate 不能依赖 undefined behavior。
- 不能触发 JVM 可见副作用。
- seed 固定时输出稳定。

测试：

- deterministic seed test。
- fake branch 不改变 observable behavior。
- optimizer 不应在保护 pipeline 内立刻移除全部 fake branch。

当前 v1 已实现一个保守子集：`BasicBlockSplittingPass` 对无 exception edge、无 block parameter、无 monitor/JMM/call/field/helper-sensitive opcode 的单 block method 插入 deterministic opaque predicate 和 fake branch。`<init>` / `<clinit>` body helper shape 只记录 `PROTECTION_STUB_BACKED_METHOD` skip，不改写 body-helper CFG。

### 基本块拆分

目标：把大 block 拆成多个小 block，扰乱线性阅读和 pattern matching。

建议实现：

- `BasicBlockSplittingPass`
- `SplitPointSelector`

边界：

- 不在 phi/block parameter 边界制造不一致。
- 不拆 required atomic region，例如 monitor enter/exit 的敏感片段。

测试：

- split 后 use/def、dominance、terminator 合法。
- runtime parity。

当前 v1 的 basic-block splitting 与 fake branch 使用同一 pass/report 入口，运行后立即通过 IR validator。block name obfuscation 可能在后续 pass 中重命名 fake block，所以测试和报告以 CFG shape 与 pass status 为准，不依赖临时 block 名。

### 常量加密

目标：整数、长整型、浮点位模式等常量不直接以明文形式出现在 IR/LLVM。

建议实现：

- `ConstantEncryptionPass`
- `ConstantEncodingStrategy`
- `ConstantDecodeSequenceBuilder`

边界：

- 注意 Java numeric conversion、NaN、signed/unsigned shift、溢出语义。
- floating constant 可先使用 bit-level encoding，不做改变数值语义的变换。

测试：

- primitive constant parity。
- edge cases：`MIN_VALUE`、`MAX_VALUE`、`NaN`、`Infinity`。

当前 v1 已接实 `CONST_INT` / `CONST_LONG` 的 deterministic XOR split/decode sequence，并在 LLVM planner/backend 中支持 `XOR_I32` / `XOR_I64` 继续进入 `LLVM_NATIVE_PATH`。`CONST_FLOAT` / `CONST_DOUBLE` 使用 `Float.floatToRawIntBits` / `Double.doubleToRawLongBits` 取得原始位模式，经过 integer XOR decode 后通过 LLVM `bitcast i32 -> float` / `bitcast i64 -> double` 恢复值；child JVM E2E 覆盖普通值、`NaN`、`-0.0` 和 infinity 的 raw-bit parity。exception、monitor/JMM、field/call/helper-sensitive method 只跳过该 pass 并写入 reason code。

### 字符串加密

目标：字符串常量不以明文保留在 native artifact 中。

建议实现：

- `StringEncryptionPass`
- `StringLiteralCollector`
- `StringEncryptionStrategy`
- `StringDecodeHelperPlanner`

边界：

- Java `String` interning 语义需要明确策略。
- 可先走 runtime helper 解密，不急着做复杂 cache。
- seed 固定时密文稳定，便于测试。

测试：

- ASCII / UTF-16 / surrogate pair parity。
- repeated literal cache policy。
- final binary string audit，确认明文不出现。

当前 v1 已接实 deterministic native-side string encryption：SSA 中的 `j2ll_rt_string_constant|string:<literal>` carrier、普通 `CONST_STRING` / `ldc String`，以及安全的 TEMPLATE constructor body string literal 都会改写为 `j2ll_rt_string_constant|enc:v1:<token>:<keyHex>:<cipherHex>` helper call。JNI helper C 生成 encrypted table，在 native side 解密后通过 `NewStringUTF` 创建 JVM `String`。该 path 不把 `String` 当作 native char pointer 长期保存。artifact audit 中 `LLVM_NATIVE_PATH`、`TEMPLATE_JNI_PATH_STABLE_SURFACE` 和 StringConcat constant carrier stable generated-C surface 是 blocking sensitive fact，并分别记录 `promotionReason=llvmNativeSurface`、`templateStableSurface`、`stableGeneratedCSurface`；report 只写 literal hash。class name / descriptor / reflection metadata token / lambda 或 MethodHandle bootstrap metadata 仍不加密；reflection-sensitive method 的普通 `CONST_STRING` 记录 `STRING_ENCRYPTION_REFLECTION_SENSITIVE` skip，相关 metadata fact 只按 `metadataSensitiveObservedOnly` 进入 observed-only evidence，避免破坏静态 metadata 解析。

### 方法内联/拆分

目标：扰乱 method 边界，提高逆向难度。

建议实现：

- `MethodInliningPass`
- `MethodSplittingPass`
- `MethodOutlinePlanner`
- `SyntheticMethodAllocator`

边界：

- 第一版优先做小型 private/static method inline。
- 方法拆分需要明确 exception、monitor、local state 和 call ABI。
- 不改变 stack trace/reflective visibility 的用户可见语义，除非 feature gate 明确允许。

测试：

- inline/split 前后 runtime parity。
- reflection-sensitive method 默认不处理。
- exception stack behavior 有明确 policy test。

### 调用间接化

目标：把直接调用改成 dispatcher/table/helper 间接调用。

建议实现：

- `CallIndirectionPass`
- `CallTargetTableBuilder`
- `CallDispatcherPlanner`

边界：

- devirtualized call 可以再间接化，但不能丢 null check/class init/exception 行为。
- 多目标 virtual/interface call 的 runtime dispatch 与 protection dispatch 要分层，避免语义混淆。

测试：

- static/special/direct call indirect parity。
- devirtualized call indirect parity。
- unresolved external fallback。

当前 v1 已接实 LLVM module model 层的保守子集：对 same-class selected static/private direct LLVM call，`LlvmCallIndirectionPass` 按 LLVM function signature 分组，默认在 module 中生成 deterministic hidden function-pointer table `j2ll_cit_<sha256>`。caller 按 seed 派生的 stable table order 取出 function pointer 并 indirect call 原 hidden LLVM function，成功记录 `CALL_INDIRECTION_TABLE`。如果 table 形态不可用，保留 deterministic hidden dispatcher switch `j2ll_cid_<sha256>` fallback，caller 传入 selector，成功记录 `CALL_INDIRECTION_DISPATCHER`。该 pass 只操作 `LlvmModule` model 和 Zig workspace 使用的 `.ll` source，不做最终 `.ll` 文本 regex；table/dispatcher symbol 使用 protection seed 稳定生成，不进入 dynamic export allowlist。当前不处理 virtual/interface generic dispatch、fallback/unresolved call、lambda/MethodHandle bootstrap metadata shape、monitor/exception/JMM-sensitive shape。无适用 direct call 的 table mode 记录 `CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE` skip。

### 虚表/方法表隐藏

目标：隐藏可读的 Java method mapping 和 dispatch table。

建议实现：

- `MethodTableHidingPlan`
- `HiddenMethodTableBuilder`
- `MethodTokenAllocator`

边界：

- 这更像 plan + runtime/backend 协作，不应只靠一个 IR pass。
- JNI wrapper/export name 仍必须可被 JVM 找到或注册。
- 内部 Java method 对应 LLVM function 必须 internal/hidden。

测试：

- method token deterministic seed test。
- symbol audit：内部 Java method name 不导出。
- RegisterNatives/JNI wrapper 仍可绑定。

## Layer 2: LLVM Module Model Protection

LLVM IR 混淆不做文本 regex 后处理。rewrite 后应先把 backend 输出拆成一个轻量 LLVM module model，再由 emitter 输出 `.ll`。

推荐包：

```text
xyz.melodysky.backend.llvm.model
xyz.melodysky.backend.llvm.pass
xyz.melodysky.backend.llvm.protection
```

推荐 pipeline：

```text
IrProgram
  -> LlvmModuleModel
  -> LlvmModulePassPipeline
  -> LlvmTextEmitter
```

### LLVM module model

推荐模型：

- `LlvmModule`
- `LlvmFunction`
- `LlvmBasicBlock`
- `LlvmInstruction`
- `LlvmTerminator`
- `LlvmGlobal`
- `LlvmType`
- `LlvmValue`
- `LlvmLinkage`
- `LlvmVisibility`
- `LlvmAttribute`
- `LlvmComdat`
- `LlvmMetadata`

边界：

- model 只表达需要生成的 LLVM subset，不追求完整 LLVM parser。
- model 必须支持 stable ordering。
- text emitter 只负责把 model 打印成 `.ll`。

当前 v1 的 LLVM name obfuscation 通过共享 `LlvmNameMangler` 接入 planner、per-class LLVM lowering、Zig workspace `.ll` 和 JNI wrapper C。启用 `protection.llvm.nameObfuscation` 时，Java method implementation symbol 变成 deterministic `j2ll_f_<sha256>`，C wrapper 只调用该 hidden linkable symbol；不再通过后置 `.ll` 文本替换重命名。

### LLVM 级混淆候选

适合 LLVM 层做：

- LLVM function/global name de-semanticization。
- helper/runtime ABI 名称混淆。
- indirect call lowering 的 native-level 形态。
- opaque predicate 的 LLVM-level 形态。
- global/string section layout 混淆。
- basic block order perturbation。
- function attribute/linkage/visibility 统一处理。

不适合 LLVM 层做：

- Java virtual dispatch 语义决策。
- class initialization 语义推断。
- exception/finally/monitor 语义修复。
- 根据 `.ll` 文本搜索替换实现混淆。

推荐类：

- `LlvmModulePass`
- `LlvmModulePassPipeline`
- `LlvmProtectionPipeline`
- `LlvmNameObfuscationPass`
- `LlvmOpaquePredicatePass`
- `LlvmBlockLayoutPerturbationPass`
- `LlvmIndirectCallPass`
- `LlvmGlobalLayoutPass`
- `LlvmVisibilityPass`

测试：

- model -> text golden test。
- pass deterministic seed test。
- pass 后 verifier/preflight test。
- generated `.ll` symbol audit。

## Layer 3: Binary Symbol Visibility And Strip

该层发生在 native build/link/package 阶段，和 IR pass 分开。

目标：

```text
Java method -> internal LLVM function / hidden symbol
JNI / C ABI wrapper -> exported symbol only
```

推荐包：

```text
xyz.melodysky.toolchain.symbols
```

推荐类：

- `ExportedSymbol`
- `ExportList`
- `SymbolVisibilityPlan`
- `SymbolVisibilityPlanner`
- `SymbolAudit`
- `StripPlan`
- `StripCommandPlanner`
- `PlatformSymbolPolicy`
- `ElfSymbolPolicy`
- `MachOSymbolPolicy`
- `CoffSymbolPolicy`

### Platform policy

Linux / ELF：

- 默认 hidden visibility。
- 使用 version script 或 linker export list。
- release build 使用 strip unneeded。

macOS / Mach-O：

- 使用 exported symbols list。
- 只导出 JNI / C ABI wrapper。
- release build strip local symbols。

Windows / COFF：

- 使用 `.def` 或 linker export list。
- 只导出必要 JNI / C ABI wrapper。
- release artifact 不生成或不打包 PDB。
- 构建产物中清理 `.pdb`。

### Symbol audit

最终动态库必须跑 symbol audit：

- exported symbols 必须是 allowlist 子集。
- Java method internal symbol 不得导出。
- helper/internal dispatcher 不得导出，除非 runtime ABI 明确要求。
- Windows artifact 不包含 PDB。

测试：

- platform export list generation test。
- symbol audit allowlist test。
- hidden Java method symbol test。
- Windows PDB cleanup test。

## Recommended Implementation Order

推荐顺序：

1. Binary symbol visibility / strip：收益大、风险低。
2. 字符串加密、常量加密、调用间接化。
3. 基本块拆分、虚假分支。
4. 复杂控制流平坦化。
5. 方法拆分/内联、虚表/方法表隐藏。
6. LLVM module model protection passes。

LLVM module model 可以和 backend rewrite 同步启动，但 LLVM 级混淆不应早于 model validator 和 symbol audit。

## Configuration

推荐配置模型：

```text
ProtectionConfig
  enabled
  seed
  irProtection
  llvmProtection
  binaryProtection
```

每个 pass 配置：

- enabled

Schema version 1 不提供 strength/intensity knob、per-pass seed override、include/exclude method filter 或 protection-specific fallback policy。需要按方法筛选时，先通过 `whiteList` / `blackList` 控制 lowering 范围；需要 deterministic 输出时，使用全局 `protection.seed`。

启用/可用性语义：

- 默认配置启用所有已实现 protection pass。
- config 启用了尚未实现的 pass 时，j2ll warning + ignore。
- pass 对某个 method 不适用时，只跳过该 pass 并 warning；不要因此把 method 从 requested lowering set 中标记为 `frontendSkipped`。
- pass 缺少硬依赖时，例如 `classPath`、JDK metadata、target toolchain capability，preflight error 并提示补齐输入或关闭该 pass。

当前 `reports/protection-report.json` 为 stable schema v1，按 pass 记录 `passName`、`layer`、`status`、`reasonCode`、`affectedMethods`、`affectedSymbols` 和 `seedHash`；raw protection seed 不写入 report、final JAR metadata、library default name 或 summary。Runtime loader 名称固定来自规范 `embeddedLibraryDirectory`，不是 seed-derived 命名。已接实 status 使用 `RAN` / `SKIPPED` / `FAILED`，未实现 pass 通过 diagnostics warning 暴露；method 级不适用使用稳定 reason code，例如 `NO_STRING_CONSTANT_CARRIER`、`NO_PRIMITIVE_CONSTANTS`、`CONTROL_FLOW_FLATTENING`、`CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE`、`CALL_INDIRECTION_TABLE`、`CALL_INDIRECTION_DISPATCHER`、`CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE`、`CALL_INDIRECTION_UNSUPPORTED_SHAPE`、`PROTECTION_CFG_SHAPE_NOT_SUPPORTED`、`PROTECTION_STUB_BACKED_METHOD` 和 `PROTECTION_MONITOR_SENSITIVE_SKIP`。

当前 fallback blob hardening v1 已在 packaging/native path 接实：可 JNI 桥接的 ordinary `halfLowered` 方法会把原 method bytecode 复制到同 owner package helper class 的 static synthetic `invoke` 方法；static wrapper 直接传原参数，instance wrapper 把 `self` 作为首参传入，primitive/reference 返回通过 JNI `CallStatic<Type>Method` 返回，pending exception 保持给 Java caller。fallback helper class bytes 先 RLE 压缩再用 SHA-256 key stream XOR 编码，写入 native artifact 中的 encoded blob manifest；JNI side 做 native-side SHA-256 校验、解码，然后通过唯一 Java 17 `<embeddedLibraryDirectory>/Loader.class` 中按需保留的 `defineHiddenFallback` 获取 owner-private `MethodHandles.Lookup` 并优先 `defineHiddenClass`。如果 JDK 不支持或 access handoff 失败，runtime 清晰回退到 JNI `DefineClass`。Loader 的 native-loading path 始终存在；没有实际 `nativeEmbeddedClassBlob` 时移除 `defineHiddenFallback`。旧 `J2llFallbackSupport.class`、`J2llNativeLoaderSupport.class` 和 `j2ll/generated/**/NativeLoader.class` 不再输出。schema v1 仍禁止输出明文 generated fallback `.class` entry。fallback report 记录 `fallbackInvokeDescriptor`、`fallbackReasonCode`、hidden-class capability (`FALLBACK_HIDDEN_CLASS` / `FALLBACK_HIDDEN_CLASS_UNAVAILABLE` / `FALLBACK_HIDDEN_CLASS_UNSUPPORTED_ACCESS`)、cache policy (`FALLBACK_CACHE_REUSE`) 和 lifecycle 字段 (`cacheScope` / `cacheKey` / `cacheLifetime` / `globalReferencePolicy` / `unloadAware=false` / `futurePath`)；runtime cache 使用 fallback id + classloader identity 的 process-lifetime linked global-ref cache，当前不承诺 classloader unload 触发释放。codec 对 wrong fallback id/key、encoded hash mismatch、corrupted/truncated RLE payload 和 decoded length capacity 做 bounded validation，避免 malformed blob 触发 unbounded allocation。`embeddedLibraryDirectory` 同时决定 Loader binary name，因此同一 defining `ClassLoader` 下不同产物复用同目录是明确已知边界，应使用应用唯一目录；独立 ClassLoader 的状态仍隔离。当前 E2E 覆盖 MethodHandle adapter chain、unsupported altMetafactory capture shape、Throwable/Thread/wait-notify fallback、mixed protected corpus 和 two-classloader isolation；unload-aware cache 仍是后续项。

Release-readiness reports include `reports/artifact-audit.json`, `reports/support-matrix.json`, `reports/opcode-support-matrix.json`, `reports/known-blockers.json`, `reports/release-readiness.json` and `reports/summary.json`, which record artifact hygiene plus protection-sensitive helper/fallback boundaries as stable feature/opcode/status/reason/testCoverage/coverageLevel/evidenceCount rows so enabled protection does not hide unsupported shapes or no-silent-skip diagnostics. Protection reports include hash-only seed identity and hash-only `sensitivePlaintextFacts` for string/constant protection inputs; original plaintext is never written to JSON reports. Each fact records `literalHash`, `sourceMethod`, `passName`, `pathKind`, `gateMode`, `sourceSurface`, `reason` and artifact surfaces. The artifact audit gate automatically consumes currently connected `LLVM_NATIVE_PATH` facts and stable TEMPLATE constructor/body helper string facts (`TEMPLATE_JNI_PATH_STABLE_SURFACE`) as blocking deny-list entries and rejects generated C/helper C/per-class LLVM/build.zig/native/JAR entry/symbol-audit/packaging sidecar leaks. Complex `HELPER_PATH` and `FALLBACK_BLOB_COMPLEX` facts remain `observedOnlySensitiveFacts` until those artifact surfaces are fully connected; they are reported but do not falsely fail the build. Artifact audit v2.2 also records generated C, per-class LLVM, build.zig, native-resource, output-JAR, symbol-audit, packaging-report and final JAR metadata surface coverage with skipped-surface reasons. Release suite workspaces additionally write `reports/release-suite-summary.json` with `profile`, `requiredCategories`, `missingCategories`, aggregate and determinism evidence; beta strict suite readiness requires dist CLI smoke/docs/report-index evidence plus beta blocker coverage, while RC strict suite readiness requires no missing categories, protected cases with artifact audit evidence, expected support evidence/report locations and child JVM differential output or expected-failure stage/reason evidence. Gate v6 requires beta/rc blocker reasons to be covered by suite expected statuses/diagnostics or weird-bytecode seeds and writes `suiteCoverageByBlocker` plus machine-readable `missingEvidence`; future blockers and explicit non-goals remain visible but do not hide protection regressions or block beta/RC readiness. All-on protection changes must continue to pass the deterministic release suite with at least one `LLVM_NATIVE_PATH` primitive method, one helper-backed path, one `nativeEmbeddedClassBlob` fallback path, dynamic reflection bridge evidence, MethodHandle bridge evidence, lambda fallback evidence, raw Unsafe/dynamic VarHandle/wait-notify boundary evidence, narrow JDK fallback cases and an artifact-audit expected-failure case; artifact audit must continue to reject plaintext fallback class entries, plaintext protected literals in covered generated C/LLVM/native/JAR/report surfaces, hidden symbol exports, legacy output paths, metadata/native SHA mismatches and packaged PDBs.

## Required Tests

每个 protection pass 至少需要：

- deterministic seed test。
- disabled pass no-op test。
- focused IR/model golden test。
- runtime parity test，如果改变 executable behavior path。
- validator/preflight test。
- dump test，确保调试输出可读。

binary protection 至少需要：

- export list generation test。
- per-platform command/flag generation test。
- symbol audit test。
- artifact cleanup test。
