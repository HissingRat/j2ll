# Protection And Obfuscation Plan

本文档定义 rewrite 后 j2ll 的保护/混淆设计。目标是把混淆分成三个清晰层次，避免把 Java 语义、LLVM emission 和 binary packaging 混在一起。

```text
SSA IR protection passes
  -> LLVM module model protection passes
  -> binary symbol visibility / strip
```

核心原则：

- 先保证语义正确，再增加强度。
- 每个 pass 都有开关、强度参数和可固定随机 seed。
- 每个 pass 声明输入 IR 形态、输出 IR 形态、是否保持 SSA、是否改变 CFG、是否需要 runtime helper。
- 不做脆弱的 LLVM `.ll` 文本后处理。LLVM IR 混淆必须基于 LLVM module model。
- 对外只导出 JNI / C ABI wrapper；Java method 对应的 LLVM function 默认 internal/hidden。

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

测试：

- branch / loop / switch flatten 后 runtime parity。
- exception path parity。
- validator 检查 CFG 和 SSA 合法。

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
4. 控制流平坦化。
5. 方法拆分/内联、虚表/方法表隐藏。
6. LLVM module model protection passes。

LLVM module model 可以和 backend rewrite 同步启动，但 LLVM 级混淆不应早于 model validator 和 symbol audit。

## Configuration

推荐配置模型：

```text
ProtectionConfig
  enabled
  seed
  intensity
  irProtection
  llvmProtection
  binaryProtection
```

每个 pass 配置：

- enabled
- intensity

Schema version 1 不提供 per-pass seed override、include/exclude method filter 或 protection-specific fallback policy。需要按方法筛选时，先通过 `whiteList` / `blackList` 控制 lowering 范围；需要 deterministic 输出时，使用全局 `protection.seed`。

启用/可用性语义：

- 默认配置启用所有已实现 protection pass。
- config 启用了尚未实现的 pass 时，j2ll warning + ignore。
- pass 对某个 method 不适用时，只跳过该 pass 并 warning；不要因此把 method 从 requested lowering set 中标记为 `frontendSkipped`。
- pass 缺少硬依赖时，例如 `classPath`、JDK metadata、target toolchain capability，preflight error 并提示补齐输入或关闭该 pass。

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
