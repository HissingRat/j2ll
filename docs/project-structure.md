# Project Structure

本文档定义 rewrite 后 j2ll 的项目结构、包边界、关键类职责，以及哪些通用逻辑应该抽成独立工具类。它和 `docs/pipeline/README.md` 的关系是：pipeline guide 说明每个编译阶段怎么工作；本文说明这些阶段在代码里怎么组织。

## Source Tree

新主线只写入：

```text
src/main/java
src/test/java
```

旧实现只作为 legacy reference：

```text
obfuscator/src/main/java
obfuscator/src/test/java
obfuscator/bench
```

规则：

- 不在 `obfuscator/src` 里添加新架构代码。
- 新测试 mirror 新包结构，放在 `src/test/java`。
- 测试 fixture、golden file、differential harness 放在 `src/test/java/.../testsupport` 或 `src/test/resources`。
- 不创建泛用 `Utils` 大杂烩。工具类必须按领域命名，并保持纯函数或无状态。

## Top-Level Packages

推荐根包仍使用：

```text
xyz.melodysky
```

目标结构：

```text
xyz.melodysky.api
xyz.melodysky.cli
xyz.melodysky.config
xyz.melodysky.pipeline
xyz.melodysky.diagnostic
xyz.melodysky.dump
xyz.melodysky.jvm
xyz.melodysky.frontend.classfile
xyz.melodysky.frontend.cfg
xyz.melodysky.analysis.hierarchy
xyz.melodysky.analysis.callgraph
xyz.melodysky.analysis.runtime
xyz.melodysky.ir.model
xyz.melodysky.ir.ssa
xyz.melodysky.ir.analysis
xyz.melodysky.ir.validate
xyz.melodysky.ir.pass
xyz.melodysky.ir.pass.protection
xyz.melodysky.backend.llvm
xyz.melodysky.backend.llvm.model
xyz.melodysky.backend.llvm.pass
xyz.melodysky.backend.llvm.protection
xyz.melodysky.runtime
xyz.melodysky.packaging
xyz.melodysky.toolchain
xyz.melodysky.toolchain.symbols
```

不是每个包都必须第一天创建。实现顺序应跟 rewrite roadmap 走。

## api

面向嵌入式调用方的稳定入口，不承载内部 pipeline 逻辑。

推荐类：

- `J2llCompiler`：public facade，接受 request，返回 result。
- `J2llCompileRequest`：输入 jar/classpath/config/output options。
- `J2llCompileResult`：输出 artifact、diagnostics、status。
- `J2llFeatureSet`：声明启用的 Java support tier、analysis 和 backend 特性。

边界：

- `api` 不直接暴露 ASM、IR、LLVM 内部模型。
- 内部 stage 变动不应强迫 public API 变化。

## cli

命令行入口和用户交互。

推荐类：

- `J2llCli`：`main` 入口，解析 argv。
- `CliCommand`：命令抽象，例如 build/analyze/dump。
- `CliOptionsParser`：只负责 CLI 参数解析。
- `CliOutput`：格式化用户可见输出。

应抽工具：

- CLI 字符串格式化可以放 `CliOutput`，不要散在 pipeline。
- path 展示可以放 `DisplayPaths`，不要混进 compiler stage。

## config

配置加载、默认值、校验。

推荐类：

- `J2llConfig`：配置根模型。
- `ConfigLoader`：读取 JSON 或默认配置。
- `ConfigValidator`：配置合法性校验。
- `ConfigSchemaVersion`：schema version 支持矩阵。
- `UnknownFieldReporter`：收集未知字段 warning，不让未知字段进入 resolved config。
- `TargetConfig`：目标平台配置。
- `FilterConfig`：`whiteList` / `blackList` selector 规则。
- `SelectorParser`：解析 class/method selector grammar。
- `SelectorMatcher`：把 selector 展开为 requested lowering set。
- `ClasspathConfig`：额外 classpath 输入。
- `JdkRuntimeConfig`：`javaHome` / `runtimeImage` 输入。
- `AnalysisWorldConfig`：`worldModel` 配置。
- `JavaSupportTierConfig`：`javaSupportTier` feature gate。
- `FallbackModeConfig`：JVM helper fallback body storage strategy，例如 `nativeEmbeddedClassBlob`。
- `SignaturePolicyConfig`：signed input JAR 策略。
- `SigningConfig`：resign 模式的 keystore 配置。
- `IntermediatesConfig`：中间产物输出开关。
- `ProtectionConfig`：保护/混淆总配置。
- `IrProtectionConfig`：SSA IR 保护配置。
- `LlvmProtectionConfig`：LLVM module model 保护配置。
- `BinaryProtectionConfig`：binary visibility/strip 配置。
- `RewriteOptions`：rewrite-only 选项，例如 dumps、tier gates。
- `ResolvedConfig`：解析默认值、相对路径、seed、selector 后的稳定配置。

边界：

- config 不读取 classfile。
- config 不创建 pipeline artifacts。
- config 必须能输出 `config.resolved.json`。

## pipeline

主线编排，只负责 stage 顺序、上下文、diagnostic 聚合和 artifact 传递。

推荐类：

- `CompilationPipeline`：按顺序运行 stage。
- `PipelineStage<I, O>`：stage 接口，提供 `name()` 和 `run(I, PipelineContext)`。
- `PipelineContext`：配置、diagnostics、dump sink、clock、workspace。
- `StageResult<T>`：stage artifact + diagnostics + completeness/conservative 标记。
- `CompilationArtifacts`：聚合最终 artifact。
- `PipelinePlan`：声明启用哪些 stage 和 pass。
- `StageNames`：稳定 stage name 常量。
- `LoweringStatus`：`lowered` / `halfLowered` / `frontendSkipped` / `notApplicable` / `failed` / `excluded`。
- `MethodEligibility`：selector 命中后是否有 lowerable body，以及不适用原因。

边界：

- pipeline 不解析 descriptor。
- pipeline 不知道 bytecode opcode 语义。
- pipeline 不修复 stage 产生的非法 artifact。

## diagnostic

跨阶段诊断模型。

推荐类：

- `Diagnostic`：单条诊断。
- `DiagnosticCode`：稳定代码，例如 `CFG_MISSING_ENTRY`。
- `DiagnosticSeverity`：info/warning/error。
- `DiagnosticLocation`：class/method/instruction/source path。
- `DiagnosticBag`：稳定排序、去重、聚合。
- `DiagnosticFormatter`：面向 CLI 或 JSON 的格式化。

应抽工具：

- stable sorting 放在 `DiagnosticBag`。
- JSON/text formatting 放在 `DiagnosticFormatter`，不要放在 stage builder。

## dump

debug dump 和可观测性。

推荐类：

- `DumpSink`：dump 输出接口。
- `NoopDumpSink`：默认关闭。
- `FileDumpSink`：写入 workspace。
- `DumpKind`：classfile/cfg/hierarchy/callgraph/runtime-analysis/ssa/optimized/protection/fallback/llvm-model/llvm-protection/llvm/native-link/symbol-audit/packaging。
- `DumpNaming`：稳定文件名。

应抽工具：

- dump 文件名清洗放 `DumpNaming`。
- artifact 序列化由各 stage printer 提供，`dump` 包只负责写入。

## jvm

JVM 低层概念。该包是多个 stage 的共享基础，但不能依赖 ASM tree。

推荐子领域类：

- `JvmType`：primitive/reference/array/void。
- `JvmPrimitive`：boolean/byte/short/char/int/long/float/double。
- `StackCategory`：category-1/category-2。
- `FieldDescriptor`：field descriptor parsed form。
- `MethodDescriptor`：method descriptor parsed form。
- `DescriptorParser`：descriptor 字符串解析。
- `DescriptorPrinter`：parsed descriptor 转回字符串。
- `JvmNames`：internal name、binary name、descriptor name 转换。
- `AccessFlags`：class/method/field flag wrapper。
- `MethodSignature`：name + descriptor。
- `FieldSignature`：name + descriptor。
- `OpcodeInfo`：opcode 分类和 JVM stack effect 基础信息。

应抽工具：

- descriptor parsing：`DescriptorParser`。
- descriptor printing：`DescriptorPrinter`。
- internal name / binary name / path 转换：`JvmNames`。
- access flag 判断：`AccessFlags`，例如 `isStatic()`、`isInterface()`、`isBridge()`。
- opcode 分类：`OpcodeInfo`，例如 `isReturn()`、`isInvoke()`、`isSwitch()`。

不要做：

- 不在 `jvm` 包里依赖 `org.objectweb.asm.tree.*`。
- 不把 IR 类型和 JVM 类型混成一个类。

## frontend.classfile

输入发现和 ASM parse。

推荐类：

- `ClassFileSource`：classfile 输入源接口。
- `ClassFileEntry`：单个 classfile bytes + source metadata。
- `JarClassFileSource`：从 JAR 读取 class entries。
- `DirectoryClassFileSource`：从目录读取 class files。
- `SingleClassFileSource`：单个 `.class`。
- `ClassFileDiscovery`：按配置发现输入。
- `AsmClassParser`：调用 ASM 并产生 parsed model。
- `ParsedProgram`：全部 parsed classes。
- `ParsedClass`：class facts。
- `ParsedField`：field facts。
- `ParsedMethod`：method facts。
- `ParsedAnnotation`：annotation metadata。

应抽工具：

- JAR entry 稳定排序：`ClassFileEntries`。
- ASM flag 到 `AccessFlags` 转换：`AsmAccessFlags`。
- ASM `Type` 到 `JvmType` 转换：`AsmTypeMapper`。
- ASM instruction 跳过 label/line/frame 的判断放 `AsmInstructions`，只允许 frontend/cfg 使用。

## frontend.cfg

method-level bytecode CFG。

推荐类：

- `MethodCfgBuilder`：入口，构建单个 method CFG。
- `BlockStartCollector`：收集 entry、branch、switch、handler、fallthrough starts。
- `InstructionIndexer`：稳定 instruction index。
- `BytecodeCfg`：CFG artifact。
- `BytecodeBasicBlock`：block id、instruction range、metadata。
- `BytecodeEdge`：from/to/kind。
- `BytecodeEdgeKind`：fallthrough/branch/switch/exception。
- `ExceptionRegion`：try range、handler、catch type。
- `BytecodeCfgValidator`：CFG invariant。
- `BytecodeCfgPrinter`：debug dump。

应抽工具：

- next executable instruction：`AsmInstructions.nextExecutable` 或迁移后的 `BytecodeInstructions.nextExecutable`。
- terminator 判断：`BytecodeControlFlow.isTerminator`。
- successor 计算：`BytecodeSuccessors`.
- block label 分配：`BlockIds`。
- handler range 判断：`ExceptionRegions`。

不要做：

- `MethodCfgBuilder` 不模拟 operand stack。
- CFG 不分配 IR value。
- CFG 不做 class hierarchy lookup。

## analysis.hierarchy

program-level class hierarchy 和 lookup。

推荐类：

- `ClassHierarchyBuilder`：从 `ParsedProgram` 构建 hierarchy。
- `ClassHierarchy`：只读查询入口。
- `HierarchyClass`：class/interface facts。
- `HierarchyMethod`：method declaration facts。
- `HierarchyField`：field declaration facts。
- `ExternalClassResolver`：JDK/third-party external type resolver。
- `AnalysisWorld`：closed/partial/JDK external/unknown dynamic world。
- `MethodLookup`：JVM method lookup。
- `OverrideResolver`：override/default method 规则。

应抽工具：

- method/field key 不再用字符串拼接，使用 `MethodSignature` / `FieldSignature`。
- override 规则放 `OverrideResolver`。
- interface default method lookup 放 `MethodLookup`，不要写在 call graph resolver 里。

## analysis.callgraph

call site 收集、CHA/RTA resolution 和 devirtualization plan。

推荐类：

- `CallSiteCollector`：从 parsed bytecode/CFG 收集 call sites。
- `CallSite`：call site id、owner method、opcode kind、declared target。
- `CallTarget`：resolved target。
- `CallGraph`：caller/callee 图。
- `CallGraphBuilder`：统一构建入口。
- `ChaCallResolver`：CHA resolution。
- `RtaCallResolver`：RTA-aware resolution。
- `CallResolutionPolicy`：unknown/external/fallback policy。
- `DevirtualizationPlanner`：生成 plan。
- `DevirtualizationPlan`：call site 到 direct/fallback decision。

应抽工具：

- deterministic call site id：`CallSiteIds`。
- invoke opcode 到 call kind：`InvokeKinds`。
- conservative unknown target：`UnknownCallTargets`。

## analysis.runtime

运行时类型、reachability、points-to、escape 等分析。

推荐类：

- `ReachabilityAnalyzer`：从 entry methods 计算 reachable methods/classes。
- `AllocationSiteCollector`：收集 `new`、array allocation、lambda allocation。
- `AllocationSite`：稳定 allocation id。
- `RuntimeTypeSet`：receiver runtime types。
- `RtaState`：RTA working state。
- `PointsToGraph`：未来 points-to。
- `EscapeAnalyzer`：未来 escape analysis。

边界：

- runtime analysis 不 lower IR。
- runtime analysis 不直接改 call instruction，只输出 facts/plan。

## ir.model

中间表示的数据模型，尽量 immutable。

推荐类：

- `IrProgram`
- `IrClass`
- `IrMethod`
- `IrBlock`
- `IrInstruction`
- `IrTerminator`
- `IrValue`
- `IrType`
- `IrFunctionType`
- `IrMetadata`
- `IrSourceMap`

边界：

- model 不做复杂构建逻辑。
- model 构造函数只做局部合法性检查。
- 跨 block、dominance、type consistency 交给 validator。

## ir.ssa

bytecode stack 到三地址 SSA 的 lowering。

推荐类：

- `BytecodeToSsaLowerer`：单 method lowering 入口。
- `SsaMethodBuilder`：构建一个 `IrMethod`。
- `SsaConstructionContext`：当前 method lowering 上下文。
- `FrameFactsBuilder`：构建 frame facts。
- `StackState`：operand stack abstraction。
- `LocalState`：local variables abstraction。
- `ValueFactory`：稳定 value id/name 分配。
- `BlockParameterPlanner`：block parameter/phi 输入规划。
- `PhiPlacement`：phi/block parameter 放置。
- `InstructionLowerer`：opcode lowerer 接口。
- `OpcodeLoweringRegistry`：opcode 到 lowerer。

推荐 lowerer：

- `LocalInstructionLowerer`
- `ConstantInstructionLowerer`
- `ArithmeticInstructionLowerer`
- `ConversionInstructionLowerer`
- `FieldInstructionLowerer`
- `InvokeInstructionLowerer`
- `InvokeDynamicInstructionLowerer`
- `ArrayInstructionLowerer`
- `TypeInstructionLowerer`
- `SwitchInstructionLowerer`
- `ExceptionInstructionLowerer`
- `MonitorInstructionLowerer`
- `ClassInitLowerer`

应抽工具：

- JVM value 到 IR value 类型映射：`JvmToIrTypes`。
- numeric conversion 规则：`NumericCoercions`。
- stack category 检查：`StackCategories` 或 `StackState` 内部方法。
- local slot 到 value mapping：`LocalSlots`。
- unsupported opcode diagnostic：`LoweringDiagnostics`。

不要做：

- 不在 lowerer 中直接 emit LLVM helper 名称。
- 不在 lowerer 中读取 Gradle/config。
- 不在 `BytecodeToSsaLowerer` 里塞所有 opcode switch；opcode 逻辑放独立 lowerer。

## ir.analysis

IR 级分析，供 validator、pass、backend 共享。

推荐类：

- `DominatorTree`
- `UseDefGraph`
- `LivenessAnalysis`
- `LoopInfo`
- `DataFlowWorklist`
- `IrTraversal`

应抽工具：

- block predecessor/successor 查询：`IrGraphs`。
- use/def 收集：`UseDefGraph`。
- dominance 查询：`DominatorTree`。
- fixed-point worklist：`DataFlowWorklist`。

## ir.validate

IR 和 SSA invariant。

推荐类：

- `IrProgramValidator`
- `IrMethodValidator`
- `SsaValidator`
- `TypeValidator`
- `ControlFlowValidator`
- `DominanceValidator`
- `ValidationIssue`

边界：

- validator 报 diagnostic，不尝试修复 IR。
- pass pipeline 每个 pass 后可运行 validator。

## ir.pass

优化和保护 pass。

推荐类：

- `IrMethodPass`
- `IrProgramPass`
- `OptimizationPipeline`
- `PassContext`
- `PassDiagnostics`
- `CanonicalizationPass`
- `DeadBlockEliminationPass`
- `ConstantFoldingPass`
- `CopyPropagationPass`
- `PhiCleanupPass`
- `DeadInstructionEliminationPass`
- `ProtectionPipeline`

应抽工具：

- IR clone/remap：`IrCloner`。
- instruction replacement：`IrRewriter`。
- pass common traversal：`IrTraversal`。
- pattern matching：`IrPatterns`。

## ir.pass.protection

SSA IR 级保护/混淆 pass。完整策略见 `docs/protection-obfuscation.md`。

推荐类：

- `ProtectionPipeline`：按配置运行保护 pass。
- `ProtectionPass`：保护 pass 接口，声明 contract。
- `ProtectionPassContract`：输入/输出 IR 形态、是否保持 SSA、是否改 CFG、是否需要 runtime helper。
- `ProtectionConfig`：enabled、seed、intensity，以及 schema v1 中每个 pass 的 enabled/intensity。
- `ProtectionRandom`：seeded deterministic random source。
- `ControlFlowFlatteningPass`
- `OpaquePredicatePass`
- `BasicBlockSplittingPass`
- `ConstantEncryptionPass`
- `StringEncryptionPass`
- `MethodInliningPass`
- `MethodSplittingPass`
- `CallIndirectionPass`
- `MethodTableHidingPlan`

应抽工具：

- dispatcher block 构建：`DispatcherBlockBuilder`。
- opaque predicate 生成：`OpaquePredicateFactory`。
- split point 选择：`SplitPointSelector`。
- 常量编码策略：`ConstantEncodingStrategy`。
- 字符串加密策略：`StringEncryptionStrategy`。
- call table 构建：`CallTargetTableBuilder`。
- method token 分配：`MethodTokenAllocator`。

边界：

- 不直接生成 LLVM 文本。
- 不处理最终 binary symbol strip。
- 不猜测 Java dispatch 语义；需要 call graph/runtime facts 时通过 `PassContext` 注入。
- 每个 pass 必须支持固定 seed 和 no-op disable。

## backend.llvm

LLVM IR lowering、LLVM module model、LLVM text emission。

推荐类：

- `LlvmTextBackend`：backend facade。
- `PerClassIrPartitioner`：按原始 class 切分待 emission 的 IR。
- `LlvmModuleLowerer`：`IrProgram` 到 LLVM module model。
- `LlvmModuleEmitter`：module-level emission。
- `LlvmFunctionEmitter`：function emission。
- `LlvmInstructionEmitter`：instruction emission。
- `LlvmTerminatorEmitter`：terminator emission。
- `LlvmTypeLowerer`：IR type 到 LLVM type。
- `LlvmNameMangler`：symbol naming。
- `LlvmHelperDeclarationCollector`：runtime helper declarations。
- `LlvmClassModulePlanner`：为每个原始 class 规划 LLVM module。
- `LlvmRuntimeAbi`：runtime ABI 约定。

应抽工具：

- LLVM identifier escaping：`LlvmIdentifiers`。
- LLVM string literal escaping：`LlvmStringLiterals`。
- symbol token hex/sanitizing：`SymbolTokens`，如果 runtime 也需要则放 `runtime` 或共享 `jvm` 外的小包。
- per-class module path calculation：`LlvmClassModulePlanner`，不要写在 text emitter 内。

不要做：

- backend 不做 devirtualization decision。
- backend 不修 CFG。
- backend 不猜 JVM 语义。

## backend.llvm.model

轻量 LLVM module model。LLVM IR 混淆基于该 model，不做 `.ll` 文本 regex 后处理。

推荐类：

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
- `LlvmModuleValidator`
- `LlvmTextEmitter`

边界：

- model 只覆盖 j2ll 需要生成的 LLVM subset，不追求完整 LLVM parser。
- text emitter 只打印 model。
- model 必须支持 stable ordering 和 deterministic dumps。

## backend.llvm.pass

LLVM module model pass 基础设施。

推荐类：

- `LlvmModulePass`
- `LlvmFunctionPass`
- `LlvmModulePassPipeline`
- `LlvmPassContext`
- `LlvmPassDiagnostics`
- `LlvmModelRewriter`
- `LlvmModelCloner`

边界：

- pass 操作 `backend.llvm.model`，不操作 `.ll` 字符串。
- pass 不做 Java 语义决策。

## backend.llvm.protection

LLVM module model 级保护/混淆。

推荐类：

- `LlvmProtectionPipeline`
- `LlvmProtectionConfig`
- `LlvmNameObfuscationPass`
- `LlvmOpaquePredicatePass`
- `LlvmBlockLayoutPerturbationPass`
- `LlvmIndirectCallPass`
- `LlvmGlobalLayoutPass`
- `LlvmVisibilityPass`

边界：

- 适合处理 native-level symbol/name/layout/call indirection。
- 不处理 Java class init、exception、monitor 或 virtual dispatch 语义。
- 不做文本后处理。

## runtime

runtime helper catalog、JNI ABI 和 stub 生成。

推荐类：

- `RuntimeHelperCatalog`：所有 helper 的注册表。
- `RuntimeHelperSignature`：helper name、args、return、exception behavior。
- `RuntimeStubGenerator`：C runtime stub 生成。
- `RuntimeAbi`：Java/JNI/native ABI 约定。
- `JniTypeMapper`：IR/JVM type 到 JNI type。
- `JniNameMangler`：JNI symbol。
- `RuntimeMetadataEmitter`：reflection/class metadata 等。
- `FallbackMode`：runtime 侧理解的 fallback storage mode。
- `FallbackHelperCatalog`：JVM helper fallback targets 和 helper definition metadata。
- `NativeEmbeddedFallbackBlob`：嵌入 native library 的 fallback class bytes metadata。
- `FallbackClassDefiner`：按 classloader 定义 hidden/generated fallback helper class。

应抽工具：

- JNI descriptor 到 C type：`JniTypeMapper`。
- JNI symbol escaping：`JniNameMangler`。
- helper name schema：`RuntimeHelperNames`。

## packaging

JAR rewrite、loader、native registration。

推荐类：

- `JarRewriter`
- `MethodRewritePlanner`
- `MethodRewriteStrategy`
- `NativeMethodRewriter`
- `ConstructorStubRewriter`
- `ClassInitializerStubRewriter`
- `InterfaceMethodStubRewriter`
- `FallbackBlobPlanner`
- `NativeEmbeddedFallbackBlobWriter`
- `FallbackBodyExtractor`
- `LoaderClassGenerator`
- `NativeRegistrationPlanner`
- `NativeRegistrationPlan`
- `RegisterNativesTableBuilder`
- `NativeLibraryExtractor`
- `Repackager`
- `ManifestMerger`
- `SignaturePolicy`
- `SignatureStripper`
- `JarSigner`
- `ResourceCopyPolicy`
- `OutputJarLayout`
- `EmbeddedLibraryLayout`
- `GeneratedLoaderNaming`
- `ClassRewriteReport`

边界：

- packaging 不 lower bytecode。
- packaging 不生成 LLVM。
- packaging 只消费 compiler output 和 runtime/native metadata。
- packaging 必须保证 output jar 中 `embeddedLibraryDirectory` 下存在所有 selected target 动态库。
- packaging 必须保留 manifest、services、module-info 和非 class resources，除非有明确 policy。
- packaging 使用 generated loader + `RegisterNatives`，不导出每个 Java method 的 JNI name symbol。
- packaging 对 `halfLowered` method 必须按 `fallbackMode` 存储 JVM helper fallback 所需 bytecode target；schema v1 使用 native embedded fallback blob，不输出明文 generated fallback class。
- packaging 对普通 class method 使用 `nativeOriginal`；对 `<init>`、`<clinit>` 和有 Code 的 interface method 使用 stub/helper strategy；abstract、already-native 和无 Code 的 interface method 记录为 `notApplicable`。

## toolchain

native build orchestration。

推荐类：

- `NativeBuildPlanner`
- `NativeBuildWorkspace`
- `ZigToolchain`
- `ZigInstaller`
- `ZigBuildWriter`
- `ProcessRunner`
- `TargetTriple`
- `BuildTarget`
- `NativeArtifactLayout`
- `IntermediateArtifactLayout`
- `ClassArtifactPath`

应抽工具：

- process output capture：`ProcessRunner`。
- target naming：`TargetTriple`。
- workspace paths：`NativeBuildWorkspace`。
- per-class intermediate paths and collision-safe class directory names：`IntermediateArtifactLayout` / `ClassArtifactPath`。

## toolchain.symbols

最终 binary export list、visibility、strip 和 symbol audit。

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
- `PdbCleanup`

边界：

- Java method 对应 LLVM function 默认 internal/hidden。
- 只有 JNI / C ABI wrapper 进入 export list。
- Linux 使用 hidden visibility、version script 或 linker export list。
- macOS 使用 exported symbols list。
- Windows 使用 `.def` 或 linker export list；release artifact 不生成或不打包 PDB，并清理 `.pdb`。
- symbol audit 必须验证最终动态库导出符号是 allowlist 子集。

## Test Structure

测试 mirror production package：

```text
src/test/java/xyz/melodysky/frontend/classfile
src/test/java/xyz/melodysky/frontend/cfg
src/test/java/xyz/melodysky/config
src/test/java/xyz/melodysky/diagnostic
src/test/java/xyz/melodysky/dump
src/test/java/xyz/melodysky/analysis/hierarchy
src/test/java/xyz/melodysky/analysis/callgraph
src/test/java/xyz/melodysky/analysis/runtime
src/test/java/xyz/melodysky/ir/ssa
src/test/java/xyz/melodysky/ir/validate
src/test/java/xyz/melodysky/ir/pass
src/test/java/xyz/melodysky/ir/pass/protection
src/test/java/xyz/melodysky/backend/llvm
src/test/java/xyz/melodysky/backend/llvm/model
src/test/java/xyz/melodysky/backend/llvm/pass
src/test/java/xyz/melodysky/backend/llvm/protection
src/test/java/xyz/melodysky/runtime
src/test/java/xyz/melodysky/packaging
src/test/java/xyz/melodysky/toolchain
src/test/java/xyz/melodysky/toolchain/symbols
```

测试支持工具：

- `testsupport.AsmFixtureBuilder`：用 ASM 构造 class/method。
- `testsupport.JavaFixtureCompiler`：编译小 Java fixture。
- `testsupport.JvmRunner`：运行 JVM baseline。
- `testsupport.DifferentialHarness`：比较 JVM 与 lowered/native 行为。
- `testsupport.GoldenFiles`：读取/更新 golden 文件。
- `testsupport.IrAssertions`：IR shape 断言。
- `testsupport.TempWorkspace`：临时 workspace。

测试工具边界：

- testsupport 可以依赖 ASM、JUnit、临时文件。
- production 代码不能依赖 testsupport。
- golden output 必须稳定排序。

## Utility Extraction Rules

允许抽成工具类的逻辑：

- 纯函数。
- 无 pipeline stage 状态。
- 输入输出小而明确。
- 被两个以上类自然共享，或属于领域基础概念。
- 名称说明领域，不叫 `Utils`。

不应该抽成工具类的逻辑：

- 需要 `PipelineContext`。
- 需要 mutable builder state。
- 只被一个类使用且不是基础概念。
- 会隐藏 JVM 语义决策。

推荐工具类清单：

```text
jvm.DescriptorParser
jvm.DescriptorPrinter
jvm.JvmNames
jvm.AccessFlags
jvm.OpcodeInfo
frontend.classfile.AsmInstructions
frontend.classfile.AsmTypeMapper
frontend.cfg.BytecodeControlFlow
frontend.cfg.BytecodeSuccessors
frontend.cfg.BlockIds
frontend.cfg.ExceptionRegions
analysis.hierarchy.OverrideResolver
analysis.hierarchy.MethodLookup
analysis.callgraph.CallSiteIds
analysis.callgraph.InvokeKinds
ir.ssa.JvmToIrTypes
ir.ssa.NumericCoercions
ir.ssa.LocalSlots
ir.analysis.IrGraphs
ir.analysis.DataFlowWorklist
ir.pass.IrCloner
ir.pass.IrRewriter
ir.pass.protection.ProtectionRandom
ir.pass.protection.OpaquePredicateFactory
backend.llvm.LlvmIdentifiers
backend.llvm.LlvmStringLiterals
backend.llvm.model.LlvmModuleValidator
backend.llvm.pass.LlvmModelRewriter
runtime.JniTypeMapper
runtime.JniNameMangler
toolchain.symbols.SymbolAudit
dump.DumpNaming
```

旧实现中类似下面的逻辑，rewrite 时应优先抽出：

- `methodKey(name, descriptor)`：改为 `MethodSignature`。
- `nextExecutable(...)` / `shouldIgnore(...)`：放 `AsmInstructions` 或 `BytecodeInstructions`。
- opcode name / opcode 分类：放 `OpcodeInfo`。
- descriptor parse / return type / parameter types：放 `DescriptorParser`。
- ASM type 到内部 type：放 `AsmTypeMapper` 和 `JvmToIrTypes`。
- helper name token escaping：放 `RuntimeHelperNames` 或 `LlvmIdentifiers`，按使用领域拆开。
- per-class LLVM module 规划：放 `LlvmClassModulePlanner`。
- path/dump 文件名清洗：放 `DumpNaming`。

## Dependency Direction

推荐依赖方向：

```text
api/cli
  -> pipeline
  -> frontend / analysis / ir / backend / runtime / packaging / toolchain
frontend
  -> jvm / diagnostic / dump
analysis
  -> frontend artifacts / jvm / diagnostic
ir
  -> jvm / analysis facts / diagnostic
backend
  -> ir / runtime metadata / diagnostic
packaging
  -> runtime metadata / toolchain artifacts
toolchain.symbols
  -> backend artifacts / toolchain artifacts / diagnostic
```

禁止方向：

- `jvm` 依赖 ASM、IR、LLVM。
- `analysis` 依赖 LLVM backend。
- `ir.model` 依赖 ASM。
- `backend.llvm` 依赖 ASM。
- `runtime` 读取 frontend mutable builder state。
- production 依赖 `testsupport`。
