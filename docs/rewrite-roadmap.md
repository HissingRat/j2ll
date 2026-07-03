# j2ll Rewrite Roadmap

本文档描述 j2ll 后续 rewrite 的目标管线、模块边界、实施阶段和完成标准。当前方向是：保留旧代码作为 legacy reference 和行为样本，但主线从干净架构重新实现，避免继续被旧的职责混杂方式牵引。新实现直接使用根目录 `src/main/java` 和 `src/test/java`，旧 `obfuscator/src` 不再承载新代码。

## 总目标

产品目标：j2ll 是 JVM-hosted JAR 混淆 / native lowering 工具。输出产物仍然是可运行 JAR，并在有 JVM 的环境中通过 generated loader、embedded native library、JNI / `RegisterNatives` 和 runtime helper 执行被 lower 的方法。

主线原则：

- 本文档只定义 JVM-hosted JAR 混淆 / native lowering；不定义任何脱离 JVM 的运行模式。
- Java object、array、Class、String、Throwable 等 Java-visible 值都属于 JVM heap，由 JVM GC 管理。
- Native-lowered code 中的 reference value 是 JNI handle / JVM object reference，不是长期可保存的 raw object address。
- `new`、array allocation、`Unsafe.allocateInstance`、reflection construction、lambda object creation 等必须通过 JVM/JNI helper 产生 Java object。
- Native temporary storage 可以使用 native stack/heap，但不能作为 Java-visible object 返回或保存。

新的主干管线应当稳定表达为：

```text
.class
  |
  v
ASM 解析 Bytecode
  |
  v
构建 CFG（每个方法）
  |
  v
构建 Class Hierarchy
  |
  v
Call Graph / Runtime Analysis
  |-- CHA
  |-- RTA
  |-- Points-to（可选）
  |-- Escape Analysis（可选）
  `-- Devirtualization
  |
  v
栈式 Bytecode -> 三地址 SSA IR
  |
  v
Optimization Pass
  |
  v
SSA IR Protection Pass
  |
  v
LLVM Module Model
  |
  v
LLVM Protection Pass
  |
  v
LLVM IR
  |
  v
Native Link / Symbol Visibility / Strip
  |
  v
Output JAR Repackaging / Loader / Native Registration
```

当前实现中，`JarIrBuilder` 负责从 JAR 读取 `.class`，`ClassIrBuilder` 和 `MethodIrBuilder` 直接把 ASM tree lower 到 `IrProgram`，其中 `MethodIrBuilder` 同时承担 CFG 起点识别、ASM frame 分析、操作数栈模拟、异常边处理、局部变量存储和 IR emission。rewrite 后，这些步骤会在新 source tree 中重建为清楚的输入输出合同。

## Legacy 备份和重写策略

rewrite 前必须先备份旧代码。推荐顺序：

1. 在开始删除或移动旧源码前，创建 `legacy/pre-rewrite` 分支。
2. 创建带日期的 tag，例如 `pre-rewrite-2026-06-25`。
3. 如需离线归档，再把仓库导出到项目外部的备份目录，不把旧源码复制进新源码树。

原则：

- 新实现不在旧 `MethodIrBuilder` 风格上继续堆逻辑。
- 旧代码只作为行为 oracle、测试样本和迁移参考。
- 可以复用旧测试意图，但测试应迁移到新 stage 边界。
- 可以复制小型、无架构污染的纯函数实现，但必须先确认它属于目标阶段。
- 不为了“保留历史”把 legacy package 放进生产 classpath。
- 新生产代码只放进 `src/main/java`。
- 新测试只放进 `src/test/java`。
- 旧 `obfuscator/src/main/java` 和 `obfuscator/src/test/java` 只读参考，不添加新架构代码。

旧源码保留在当前 `obfuscator/src` 位置作为参考即可。后续调整 Gradle 时，主线 source set 应切到 `src/main/java` 和 `src/test/java`；如需对照旧实现，可以显式创建 legacy-only 任务，但不要让旧 tree 混入新主线编译。

## 设计原则

- 每个阶段只依赖前一个阶段的稳定数据结构，不回读上一层内部临时状态。
- ASM tree 是前端输入细节；中后端不直接依赖 ASM 节点。
- CFG 是 method-level 的事实模型；class hierarchy 和 call graph 是 program-level 的事实模型。
- Runtime analysis 先保守正确，再逐步增加精度。CHA 是第一版必需能力，RTA、points-to、escape analysis 可以作为可选增强。
- SSA IR 是优化和 LLVM backend 的边界。进入 optimization pass 之前，IR 需要完成类型、控制流、值定义和调用目标的基本校验。
- 不为“看起来更抽象”而抽象；每个新包或接口都应该承载一个明确阶段。
- 遇到不确定语义时选择 conservative fallback，不静默生成可能错误的 native code。
- 每个 stage 都应有 validator、diagnostic 或 focused tests，不能把正确性压力推给最终 LLVM/backend。

## 健壮性加强方向

当前路线需要补强这些层，让 JVM-hosted lowering 像成熟 compiler pipeline 一样可验证、可回退、可观测：

- 字节码语义模型：明确 JVM stack map frame、category-1/category-2 value、monitor、exception、class init、array store check、null check、numeric conversion、invokedynamic 等语义。
- Classpath/world model：区分完整 classpath、partial world、JDK external world、unknown dynamic loading，并让每个分析知道自己的精度边界；这里的 world model 只服务 JVM-hosted analysis / obfuscation。
- 分层 fallback：当 hierarchy、RTA、points-to 或 devirtualization 不确定时，回退到 JVM/JNI/runtime helper，而不是猜测。
- Stage validator：parse、CFG、hierarchy、call graph、SSA、optimization、LLVM emission 前后都要有可测试的不变量。
- Canonical IR：在优化前把 IR 规整到少数稳定形态，降低 pass 和 backend 复杂度。
- Deoptimization / runtime guard 预留：如果未来做激进优化，应预留 guard/fallback 表达能力。第一版可以不实现 deopt，但 IR 设计不要封死。
- Determinism：同一输入应产生稳定 diagnostics、IR ordering、LLVM symbol ordering，方便测试和回归定位。
- Differential testing：用小型 Java fixture 对比 JVM 运行结果与 native-lowered 结果。
- Fuzz / corpus testing：用生成的 bytecode 或收集的真实 class corpus 压 CFG、verifier、lowering 和 backend；当前 `testsupport.corpus.CorpusRunner` 固化 original/output child JVM exit code/stdout differential，`WeirdBytecodeSeedCorpusTest` 固化 stack/switch/finally/monitor boundary seed。
- Observability：每个 stage 可以输出 debug dump，便于定位是 CFG、analysis、SSA 还是 backend 出错。Beta packaging polish 已加入 runnable `build/cli/j2ll.jar`、`build/dist/j2ll/` distribution、`docs/getting-started.md`、managed Zig bootstrap checksum/user-flow events、`reports/index.json` v2、带 native target 摘要的 `reports/summary.md`、diagnostic hints、executable docs sample projects、`betaAcceptance` dist-level验收入口和 beta readiness profile，方便外部用户试跑和定位失败。

Java / JVM 特性的分层支持范围见 `docs/java-support-tiers.md`。新增语言特性、JDK runtime 支持或动态特性支持时，应先确认所属 tier，再补对应测试。

rewrite 后的包结构、关键类职责和工具类抽取规则见 `docs/project-structure.md`。

保护/混淆路线见 `docs/protection-obfuscation.md`。SSA IR 混淆、LLVM module model 混淆、binary symbol visibility/strip 必须分层实现。

输入 JAR、`config.json` 字段、输出 JAR、动态库布局和中间产物布局见 `docs/io-config-output-contract.md`。

## 推荐包结构

目标结构可以按下面方向迁移。实际提交时允许渐进过渡，但新增能力应尽量放进目标边界中。

```text
xyz.melodysky.frontend.classfile
  ClassFileSource
  AsmClassParser
  ParsedClass
  ParsedMethod

xyz.melodysky.frontend.cfg
  MethodCfgBuilder
  BytecodeBasicBlock
  BytecodeCfg
  ExceptionRegionModel

xyz.melodysky.analysis.hierarchy
  ClassHierarchy
  ClassHierarchyBuilder
  MethodSignature
  FieldSignature

xyz.melodysky.analysis.callgraph
  CallGraph
  CallGraphBuilder
  ChaCallResolver
  RtaCallResolver
  DevirtualizationPlan

xyz.melodysky.analysis.runtime
  RuntimeAnalysisPipeline
  AllocationSiteModel
  PointsToResult
  EscapeAnalysisResult

xyz.melodysky.ir.ssa
  BytecodeToSsaLowerer
  SsaConstructionContext
  PhiPlacement
  StackState

xyz.melodysky.ir.pass
  OptimizationPass
  MethodOptimizationPass
  ProgramOptimizationPass
  protection/ProtectionPipeline

xyz.melodysky.backend.llvm
  LlvmTextBackend
  model/LlvmModule
  protection/LlvmProtectionPipeline

xyz.melodysky.runtime
  RuntimeHelperCatalog
  FallbackHelperCatalog
  NativeEmbeddedFallbackBlob

xyz.melodysky.packaging
  Repackager
  MethodRewritePlanner
  LoaderClassGenerator
  NativeRegistrationPlanner

xyz.melodysky.toolchain
  NativeBuildPlanner
  IntermediateArtifactLayout
  ZigToolchain
  ManagedZigLocator
  ZigArchiveResolver
  ZigDownloader
  ZigArchiveExtractor
  ZigBuildWriter
  ZigLlvmInput
  ZigObjectInput

xyz.melodysky.toolchain.symbols
  SymbolVisibilityPlanner
  SymbolAudit
  StripCommandPlanner
```

旧 `frontend.bytecode` 只作为 legacy reference。若需要保留兼容 API，应在 `src/main/java` 中重建薄 facade；新增的 CFG、hierarchy、call graph、SSA 能力不要继续堆进旧 `MethodIrBuilder`。

## 分阶段路线

### Phase 0：文档和边界冻结

目标：

- 明确目标管线、模块边界、测试要求和维护规则。
- 明确 legacy 备份策略和从零重写策略。
- 不改业务代码，只新增或更新文档。

完成标准：

- `docs/rewrite-roadmap.md` 描述总路线图。
- `docs/pipeline/README.md` 和 `docs/pipeline/*.md` 描述每阶段实现 guide。
- `AGENTS.md` 写清测试、git 检查频率、rewrite 边界维护方式。

### Phase 0.5：Legacy freeze 和 clean-room bootstrap

目标：

- 给旧实现打可恢复快照。
- 在新架构入口启动前切断旧实现继续膨胀的路径。

建议动作：

- 建立 `legacy/pre-rewrite` 分支和日期 tag。
- 记录当前测试命令和旧 pipeline 的用户可见行为。
- 新建最小 clean-room source structure：`src/main/java` 和 `src/test/java`。
- 先建立 empty pipeline skeleton、stage result、diagnostic、validator 接口，再开始迁移功能。

完成标准：

- 旧代码可以通过分支或 tag 恢复。
- 主线生产 source set 不依赖 legacy package。
- 新代码和新测试只出现在根目录 `src/main/java`、`src/test/java`。
- 第一批新测试验证 stage skeleton 和 diagnostic 输出。

### Phase 1：拆出 ClassFile / ASM parse 层

目标：

- 把“读取 JAR / `.class`”和“ASM 解析结果”从 IR lowering 中拆开。
- 让后续 CFG、hierarchy、call graph 都能共享同一份 parsed class facts。

建议迁移：

- 如需兼容旧调用方，在 `src/main/java` 中重建 `JarIrBuilder` 风格的薄入口，内部委托给 `frontend.classfile`。
- 引入 `ParsedClass`，包装 class internal name、access、super name、interfaces、fields、methods、raw `ClassNode` 或必要 ASM facts。
- 先不要强行删除 `ClassNode`，但限制它只在 frontend 边界内传播。

完成标准：

- JAR 读取测试仍通过。
- 对单个 `.class` / `ClassNode` 的解析可以单测。
- frontend skip report 的行为保持不变。

### Phase 2：显式构建 Method CFG

目标：

- 在 lower 到 IR 之前，为每个 method 生成独立的 bytecode CFG。
- CFG 节点表示 basic block，边表示 fallthrough、branch、switch、exception handler。

建议迁移：

- 以旧 `MethodIrBuilder` 的 block start 收集、label 分配、exception handler 入口识别行为为参考，在 `src/main/java` 中重建 CFG builder。
- `BytecodeCfg` 不负责模拟 operand stack，也不生成 IR value。
- CFG builder 应该接受 `ParsedMethod` 或 `MethodNode`，返回可校验的数据结构。

完成标准：

- 分支、goto、switch、try/catch、空方法、不可达块都有 CFG 单测。
- CFG 测试只断言 bytecode-level block/edge，不断言 IR 指令。
- 旧 `MethodIrBuilderTest` 中纯 CFG 期望逐步迁移为新 `src/test/java` 下的 `MethodCfgBuilderTest`。

### Phase 3：构建 Class Hierarchy

目标：

- 从所有 parsed classes 构建 program-level class hierarchy。
- 为 virtual/interface call resolution 提供统一事实来源。

建议迁移：

- `ClassHierarchy` 记录 class、interface、super class、implemented interfaces、method declarations、field declarations。
- 对缺失的 JDK / third-party class 使用 conservative external class node，不阻塞整包分析。
- hierarchy 构建不做 call graph，不做 lowering。

完成标准：

- 支持普通继承、接口实现、接口继承、abstract/native method、missing external type。
- hierarchy 查询有单测：subtypes、overrides、method lookup、interface implementors。

### Phase 4：Call Graph / Runtime Analysis

目标：

- 在 IR lowering 前建立可复用的调用事实。
- 让 invoke lowering 不再把所有 virtual/interface 调用都视为同一种 runtime helper 路径。

建议迁移：

- 先实现 CHA：根据 declared receiver type 和 class hierarchy 找到保守目标集合。
- 再实现 RTA：从 entry/native-lowered methods 出发，只把 reachable allocation types 纳入 virtual dispatch 目标集合。
- Points-to 和 escape analysis 保持可选，不影响第一版主线。
- Devirtualization 输出 `DevirtualizationPlan`，只描述哪些 call site 可以变成 direct/special/static-like call，不直接改 ASM。

完成标准：

- CHA 单测覆盖 final class、final method、interface call、多实现类、missing external type。
- RTA 单测覆盖未实例化 subtype 不进入目标集合、反射/unknown allocation 触发保守回退。
- Devirtualization 单测覆盖单目标、多目标、外部未知目标。

### Phase 5：栈式 Bytecode -> 三地址 SSA IR

目标：

- 用显式 CFG、frame facts、runtime analysis facts 来 lower bytecode。
- IR 进入 optimization pass 前已经是三地址 SSA 风格，值定义清楚，block 参数或 phi 语义清楚。

建议迁移：

- 以旧 `MethodIrBuilder` 的行为为参考，在新 source tree 中重建 `BytecodeToSsaLowerer`、`StackState`、`LocalState`、`InstructionLowerer`。
- 每类 opcode lowering 都有独立文件或小范围 helper：locals、constants、arithmetic、field、invoke、array、type、switch、exception。
- 对 stack merge 使用明确的 phi/block parameter 模型，而不是隐式 spill 到 synthetic local。若短期兼容现有模型，应在 guide 中标注为过渡策略。

完成标准：

- 每新增一个 opcode lowering，必须新增对应测试。
- SSA validator 能检查 use-before-def、类型、terminator、block 参数/phi arity。
- 现有 `IrMethodValidator` 可以保留并逐步升级，不让 LLVM backend 承担 IR 正确性检查。

### Phase 6：Optimization Pass

目标：

- 把保护/混淆 pass 和通用优化 pass 放进清楚的 pass pipeline。
- 区分 method pass 与 program pass。

建议迁移：

- 保留当前 `IrMethodPass`，新增 `IrProgramPass` 或统一 `OptimizationPass` 时要明确粒度。
- 基础优化优先级：CFG cleanup、dead block elimination、constant folding、copy propagation、trivial phi cleanup。
- 保护 pass 优先保持现有行为：string obfuscation、constant splitting、CFG perturbation。

完成标准：

- 每个 pass 有单测，测试输入尽量是手写 IR model，不依赖完整 frontend。
- pass pipeline 每步后运行 validator。
- pass 顺序在一个集中配置点维护。

### Phase 7：LLVM Module Model 和 LLVM IR Backend

目标：

- LLVM backend 只消费验证后的 IR 和 runtime/helper metadata。
- 按原始 class 生成 per-class LLVM module model 和 `.ll`。
- 后端不再推断前端语义，不修补非法 IR。

建议迁移：

- 保留 `LlvmTextBackend` 的文本输出能力，但拆出 name mangling、type lowering、helper declaration collection、function emission。
- Direct call / runtime helper lowering 应消费 call graph 或 devirtualization 结果，而不是在 backend 内临时重建调用语义。
- 引入 `backend.llvm.model`，后续 LLVM 级混淆只操作 model，不做 `.ll` 文本 regex。

完成标准：

- backend 单测覆盖 primitive/reference type、control flow、invoke、helper declaration、per-class module emission。
- e2e 测试覆盖至少一个 virtual call 被 devirtualize 的样例，以及保守回退样例。

当前实现状态：

- 已有 per-class `LlvmModule` / `.ll` emission，默认 Java method function 为 `internal hidden`。
- Host-only `LLVM_NATIVE_PATH` 已接实真实 native lowering：ordinary static/instance primitive/reference-handle method 从 Bytecode -> SSA IR -> per-class LLVM module / `.ll` -> hidden linkable LLVM function -> JNI wrapper -> `RegisterNatives` -> output JAR child JVM E2E。
- 当前 `LLVM_NATIVE_PATH` 覆盖 static int add、long arithmetic、double arithmetic、boolean compare branch、void no-op、if/else return、nested if、block-parameter/phi merge、table/lookup switch、JVM numeric helper opcode subset、protected float/double constant raw-bit encryption through integer XOR + LLVM bitcast、protected CFF dispatcher switch for simple branch methods、static/instance/volatile field helper path、monitor/synchronized helper path、explicit throw bridge、static reflection method/constructor/field/setAccessible helper path（含 no-arg、reference、primitive 和 array 常量参数 method/constructor invoke、typed Field int/boolean/long/double/reference accessor、private method/constructor accessible smoke，report 拆分 `REFLECTION_METHOD_HELPER` / `REFLECTION_CONSTRUCTOR_HELPER` / `REFLECTION_ACCESSIBLE_HELPER` / `REFLECTION_FIELD_HELPER` / `REFLECTION_HELPER`）、dynamic reflection bridge path、Unsafe statically resolved `Field` token 的 `objectFieldOffset` / `getInt` / `putInt` / monitor-backed `compareAndSwapInt` / `allocateInstance` JNI helper path、typed-int VarHandle helper path、null receiver NPE ownership、String/reference field pass-through、ordinary `CONST_STRING` encrypted helper path、div/rem ArithmeticException helper、`byte[]` / `short[]` / `char[]` / `int[]` / `long[]` / `float[]` / `double[]` / reference array helper subset、System.arraycopy byte/int/long/double/object/overlap/null/oob/ArrayStoreException helper、selected primitive/reference array allocation helpers、ordinary-method object construction helper subset、`checkcast` / `instanceof` helper subset、String `length` / `equals` / `isEmpty` / `charAt` / `startsWith` / `endsWith` / `substring(int,int)` helpers、显式 StringBuilder append chain、StringConcatFactory `makeConcat` / common `makeConcatWithConstants`、LambdaMetafactory common `metafactory` helper path、LDC MethodHandle + `invokeExact` direct path、MethodHandle adapter chain bridge path、Math `abs/min/max` int/long/float/double helper subset、Integer/Long/Boolean/Double boxing-unboxing、Objects.requireNonNull/equals、same-class selected static/private-special caller -> selected callee direct LLVM call，以及该 direct call 的 hidden table indirection、tokenized virtual/interface/default-interface JVM dispatch helper 的 no-arg int、int-arg int、reference return 和 single-reference-argument/reference-return subset，report 同时标注 `DISPATCH_HELPER` / `DEFAULT_INTERFACE_DISPATCH_HELPER` / `DEFERRED_DISPATCH_HELPER`，default-interface conflict/diamond boundary 追加 `UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT` / `DEFAULT_INTERFACE_DISPATCH_FALLBACK`，default-interface super `I.super.m()` 因 helper-class verifier direct-superinterface 限制保守 `frontendSkipped` + `UNSUPPORTED_DEFAULT_INTERFACE_SUPER`。Encoded `nativeEmbeddedClassBlob` fallback 已覆盖 ordinary `halfLowered` bytecode-preserving helper body，包括 unsupported JDK call、ArrayList/HashMap/Arrays/Collections/Optional/String.format narrow `JDK_COLLECTION_HELPER` site + `JDK_HELPER_FALLBACK` policy（含 contains/overwrite stress）、Throwable message/cause common path、Thread start/join common path、wait/notify boundary、unsupported altMetafactory two-capture serializable lambda（`ALT_METAFACTORY_FALLBACK`）、instance receiver/reference return、exception propagation 和 two-classloader isolation。fallback blob codec 已对 wrong fallback id/key、hash mismatch 和 corrupted RLE payload 做 bounded validation。full constructor/object semantics、complex finally/exception state merge/monitor-finally interaction/nested finally、reflection shapes beyond the JVM bridge matrix、raw memory Unsafe/VarHandle、complex CFF shape、arbitrary reference/object-heavy LLVM path、MethodHandle interpreter、full altMetafactory runtime helper 和 更复杂 virtual/interface dispatch 仍走 `TEMPLATE_JNI_PATH` / helper / fallback。
- 为了 C wrapper 跨 object 链接，native build artifact 使用 `external hidden` LLVM function；默认 debug dump 仍保持 `internal hidden`。symbol audit 必须确认 LLVM implementation symbol 不导出。

### Phase 8：Protection / Obfuscation 分层落地

目标：

- 把 SSA IR protection、LLVM module model protection 和 binary symbol hardening 分开实现。
- 默认开启已实现 protection pass；未实现 pass warning + ignore；单 method 不适用时只跳过该 pass。

建议迁移：

- 先做 binary symbol visibility / strip / symbol audit。
- 再做字符串/常量加密、调用间接化、基本块拆分、虚假分支。
- 复杂控制流平坦化、方法拆分/内联、虚表/方法表隐藏放在后续增强。

完成标准：

- 每个 protection pass 有 deterministic seed test、disabled no-op test、validator test。
- symbol audit 能验证 Java method internal symbol 不导出。
- protection report 能说明 pass 是否运行、跳过或尚未实现。

当前 v1 状态：

- 已接实 IR `StringEncryptionPass`、`PrimitiveConstantEncryptionPass`、`ControlFlowFlatteningPass`、`BasicBlockSplittingPass` / fake branch 和 `BlockNameObfuscationPass`，并在 all-on JVM-hosted E2E 中验证不破坏现有 native lowering vertical slice。`PrimitiveConstantEncryptionPass` 现在覆盖 `int` / `long` XOR split，以及 `float` / `double` raw-bit XOR + LLVM bitcast，包含 `NaN`、`-0.0` 和 infinity 的 child JVM raw-bit parity；`ControlFlowFlatteningPass` 当前覆盖安全 simple branch shape，复杂 shape 记录 `CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE`。
- LLVM name obfuscation 已改为共享 `LlvmNameMangler`，planner、LLVM module lowering、Zig workspace `.ll` 和 JNI wrapper C 使用同一 deterministic `j2ll_f_<sha256>` symbol；symbol audit 仍只允许 loader/bootstrap exports。
- `reports/protection-report.json` 已有 golden test；未实现 pass 继续 warning + ignore，单 method pass inapplicability 记录 reason code 而不改变 lowering status。

### Phase 9：Native Build / Link / Symbol Audit

目标：

- 通过 managed Zig `0.15.2` 为 selected target 生成动态库。
- 只导出 loader/bootstrap JNI wrapper 和必要 C ABI wrapper。
- 内部 Java method/native helper symbols 默认 hidden/internal。

建议迁移：

- 建立 `toolchain.NativeBuildPlanner`、target naming、workspace layout 和 managed `ZigToolchain` capability/preflight。
- `ZigToolchain` 固定解析可执行 `j2ll.jar` 同级的 `zig/zig(.exe)`；缺失或版本不匹配时，先查找同目录 Zig `0.15.2` archive，找不到再从 Zig 官方 download path 下载；local/downloaded archive 必须按内置官方 SHA-256 metadata 校验，通过后才解压并将官方目录内容规范化到 `zig/`；signature verification 当前明确报告 `notVerifiedBoundary`。
- `ZigBuildWriter` 生成统一 build plan，接收 per-class `.ll`、Zig-managed `.o`、JNI wrapper C、runtime helper C 和 fallback blob carrier sources。
- `.ll` 到 target object、`.o` link、动态库命名、target triple、export list、strip/remove-PDB policy 都必须通过 Zig toolchain 编排。
- 建立 `toolchain.symbols` 的 export list、strip plan 和 platform policy。
- 让 symbol audit 成为成功输出的必经 validator。

完成标准：

- 每个 selected target 在 `native/<target>/` 产出固定名称动态库。
- Windows release artifact 不打包 PDB。
- `reports/symbol-audit.json` 记录 allowlist、actual exports 和 audit result。
- Release-readiness gate 写入 artifact audit、support/opcode matrix、known blockers、readiness result 和用户摘要 `reports/summary.json`；deterministic release suite 已覆盖 minimal static add、mixed LLVM/helper/fallback/protection path、config missing schema/invalid selector failure、unknown field warning success、safe finally cleanup、signed fail/strip/resign、service loader + multi-release + module-info preservation、reflection/MethodHandle/lambda fallback、raw Unsafe boundary、dynamic VarHandle boundary、wait/notify boundary、required non-host Zig target failure、artifact audit expected failure、JDK collection/Optional/Throwable/Thread fallback，以及 realistic CLI/reflection/packaging samples，并写入带 `profile`、`requiredCategories`、`missingCategories`、aggregate 和 `determinismEvidenceComplete` 的 `reports/release-suite-summary.json` 供 strict suite readiness mode 校验；beta profile 要求 dist CLI smoke、docs examples、report index 和 beta blocker evidence，uncovered beta blocker 使 `betaProfilePassed=false`；RC profile 要求 missingCategories 为空。artifact audit v2.2 检查 output JAR/native resource hygiene、final JAR metadata/targetArtifacts consistency、reports manifest hash/entries、embedded native SHA-256、hidden symbol export、PDB exclusion、legacy output path、plaintext fallback class ban、fallback blob binary metadata/carrier surface 和 sensitive plaintext facts；connected `LLVM_NATIVE_PATH`、`TEMPLATE_JNI_PATH_STABLE_SURFACE` 与足够具体的 StringConcat constant carrier stable generated-C facts 是 blocking deny-list；短/通用 literal 只做 hash-only observed evidence，reflection/lambda/MethodHandle metadata facts 保持 observed-only evidence。audit fail 是 finalization gate，会移除/不保留 final JAR 并写 `ARTIFACT_AUDIT_FAILED` failure report。gate v6 要求 release-blocking known blocker reason（`beta-blocker` / `rc-blocker`）有 support/opcode matrix row，并由 release suite expected status/diagnostic 或 weird-bytecode seed 覆盖；future blocker 和 explicit non-goal 可见但不阻塞 beta/RC。expected failure case 记录 stage/reason、failure report 和 `finalArtifactWritten=false`；`release-readiness.json` 额外写出 `missingEvidence`、`suiteCoverageByBlocker`、`blockerEvidenceComplete`、`targetEvidenceComplete`、`finalArtifactWritten`、`determinismEvidenceComplete`、`metadataConsistencyPassed`、`blockingSensitiveFactsPassed`、`targetPackagePlanComplete` 和 `strictModePassed`。主要 reports 现在同时写 `schemaVersion` 和 `reportVersion`，并且 protection/config reports 只写 seed hash。

当前实现状态：

- 已接实当前 host target 的 managed Zig `build.zig` dynamic-library build vertical slice。它现在通过 j2ll 生成的 Zig workspace 同时编排 JNI wrapper C、runtime/fallback carrier C 和 per-class LLVM `.ll` input：`LLVM_NATIVE_PATH` 覆盖 ordinary static/instance primitive/reference-handle methods、field helper-backed `int`/`long`/reference access、Unsafe statically resolved int field token helper、typed-int VarHandle helper、monitor/synchronized helper、explicit throw bridge、static reflection helper、volatile fence markers、div/rem ArithmeticException helper、broad primitive/reference array helpers、selected primitive/reference/object allocation helpers、type check helpers、String helper subset、StringConcatFactory common paths、LambdaMetafactory common helper、LDC MethodHandle direct path、Math int/long helper subset、same-class selected static/private-special direct call 和 narrow no-arg `int` virtual/interface dispatch helper；`TEMPLATE_JNI_PATH` 覆盖 String content path、`int[]` copy path、exception bridge smoke、generic straight-line/simple-branch constructor/class-initializer body helper、encoded native-embedded fallback bytecode-preserving helper body path，以及当前更广泛 reference/object-heavy semantics。
- selected target matrix 的 build plan、`build.zig`、artifact layout、target preflight 和 packaging report 已稳定；当前 host target 真实构建。schema v1 selected target 默认 required，非当前 host 或当前 preflight 不可构建的 required target 进入 `failedTargets`，diagnostic 使用 `ZIG_TARGET_UNBUILDABLE`，记录 required/currentHost/buildable、Zig triple、expected artifact path/name、failure kind、required capability、platform SDK requirement 和 build log tail，并使 pipeline failed 且不写 final output JAR；optional/report-only target simulation 仅保留在 focused toolchain tests。真实交叉编译、strip/PDB 细节和 SDK 能力仍由后续 managed Zig capability/preflight 决定。后续 Phase 9 工作应继续在这个 Zig 管线下扩 target coverage、export policy 和 symbol audit。

### Phase 10：Packaging / Loader / Native Registration

目标：

- 输出仍然是可运行 JAR。
- 原 method body rewrite、native registration、fallback blob 和 embedded libraries 都由 packaging 阶段统一落地。

建议迁移：

- 普通 class method 使用 `nativeOriginal`：原方法去 Code、加 `ACC_NATIVE`、用 `RegisterNatives` 绑定。
- `<init>` 使用合法 constructor stub + native body helper。
- `<clinit>` 使用 loader/bootstrap stub + native body helper。
- 有 Code 的 interface method 使用 interface method stub + generated native helper。
- abstract、already-native 和无 Code 的 interface method 记录 `notApplicable`。
- JVM helper fallback 使用 `nativeEmbeddedClassBlob`；不输出明文 generated fallback class。

完成标准：

- output JAR 保留 manifest、resources、services、module-info、multi-release metadata。
- output JAR 在配置的 `embeddedLibraryDirectory` 下包含 selected target 动态库。
- loader 可选择、校验、加载动态库并执行 `RegisterNatives`。
- packaging report 能列出 generated loaders、rewritten classes、registration summary 和 fallback blob metadata。

当前实现状态：

- 当前 host E2E 已能在 child JVM 中运行 output JAR，并通过 generated loader、SHA-256 校验、`System.load`、`JNI_OnLoad` 和 `RegisterNatives` 绑定 native methods；embedded dynamic library 来自 managed Zig `build.zig` workspace。
- report 已记录 generated loader、rewritten methods、embedded library path/SHA、registered native methods、registration groups、exported symbols、JAR preservation summary、signature action、每个 selected target 的 expected artifact path/name、当前 host actual SHA-256/exported symbols、failed target reason、required capability、platform SDK requirement、failure kind、build log tail、Windows PDB exclusion policy、final JAR `META-INF/j2ll/*` metadata、`reports/artifact-audit.json` / `reports/support-matrix.json` / `reports/opcode-support-matrix.json` / `reports/known-blockers.json` / `reports/release-readiness.json` release-readiness artifacts，以及每个 lowered method 的 `nativeImplementationPath`。failure runs 写入 `reports/failure-report.json` 且 `finalArtifactWritten=false`。protection report 记录 hash-only `sensitivePlaintextFacts` 的 `pathKind/gateMode/sourceSurface/reason`，artifact audit 自动消费当前已接实 deny-list facts，并报告 observed-only facts 和 surface coverage。release suite 测试还写入 `reports/release-suite-summary.json`，记录 suite profile、required/missing categories、suite/case metadata、aggregate、determinism evidence、expected support statuses、expected support evidence/report location、original/output child JVM result 或 config/toolchain/artifact expected failure null run、produced report paths、diagnostics、signature policy、protection variant、expected failure stage/reason 和 final artifact state。
- 当前真实 E2E 覆盖 `LLVM_NATIVE_PATH` 的 static primitive scalar add/long/double/boolean compare/void no-op/if-else/nested-if/phi merge、table/lookup switch、JVM numeric helper opcodes、protected float/double constant raw-bit encryption、protected CFF dispatcher switch for simple branch methods、static/instance/volatile field helper path、monitor/synchronized helper path、explicit throw bridge、static reflection helper path（含 primitive/reference/array descriptor matrix、typed Field int/boolean/long/double/reference accessor）、dynamic reflection bridge path、Unsafe statically resolved int field token helper path、typed-int VarHandle helper path、LDC MethodHandle direct path、MethodHandle adapter chain bridge path、null receiver NPE ownership、String/reference field pass-through、ordinary `CONST_STRING` encrypted helper path、div/rem ArithmeticException helper、broad primitive/reference array helpers、selected primitive/reference array allocation helpers、ordinary-method object construction helper subset、`checkcast` / `instanceof` helper subset、String `length` / `equals` helper、StringConcatFactory `makeConcat` / `makeConcatWithConstants`、LambdaMetafactory common helper、Math int/long helper subset、direct static/private-special callee call hidden table indirection 和 no-arg `int` virtual/interface/default-interface dispatch helper与 conflict boundary report；default-interface super `I.super.m()` 当前作为 frontendSkipped no-rewrite boundary 覆盖；template/helper path 覆盖 multi-class/multi-method registration、String content `jstring`、primitive `int[]` copy/new-array、`ThrowNew` exception bridge smoke、generic straight-line/simple-branch constructor/class-initializer body helper、TEMPLATE constructor body string literal encrypted helper path，以及 encoded `nativeEmbeddedClassBlob` fallback 的 unsupported JDK call、ArrayList/HashMap/Arrays/Collections/Optional/String.format narrow policy、Throwable message/cause common path、Thread start/join common path、wait/notify boundary、unsupported altMetafactory capture shape、instance receiver/reference return、exception propagation 和 two-classloader isolation。
- Config/CLI 当前已有 `docs/config.schema.json`、`docs/examples/*.json`、`j2ll validate`、`j2ll dry-run`、短 stdout/stderr、exit code mapping 和 `summaryReport=...` 输出。Intermediate artifact 当前写 `intermediates/intermediates-manifest.json`，记录 config switches、class/method artifact ids 和实际写出文件 SHA-256；debug dump / per-class IR / LLVM / C 文件受 `intermediates` 开关控制。
- Interface method declaration stubs、full object/reference LLVM semantics、constructor/class-initializer shapes beyond the straight-line/simple-branch helper subset、complex finally/exception state merge/monitor-finally interaction/nested finally、reflection shapes beyond the JVM bridge matrix、wait/notify native helper、broader virtual/interface dispatch helper implementation、unload-aware fallback cache 和非当前 host target 的真实交叉编译/SDK capability 仍是后续扩展项。

### Phase 11：Config / Reports / Artifact Contract 固化

目标：

- `config.json` schema、unknown field 策略、selector grammar、report JSON schema 和 artifact naming 都稳定下来。
- 缺少 required field 直接 preflight error；未知字段 warning 但不参与 resolved config。

建议迁移：

- 实现 `schemaVersion`、`javaSupportTier`、`worldModel`、`fallbackMode`、signature policy 和 protection config。
- selector grammar 支持 class/method exact selector 和 class wildcard selector。
- per-class intermediate artifact 使用 safe class path + SHA-256 prefix；method id 使用 descriptor hash。

完成标准：

- `config.resolved.json`、diagnostics、frontend skip、lowering、packaging、protection、symbol-audit、support/opcode matrix、known blockers、release-readiness reports 和 release suite summary 都有 golden/focused test。
- class/method artifact path collision test 覆盖大小写、Unicode、overload 和 nested class。

## Rewrite 策略

- 优先在新 source tree 建立新阶段和新适配层，再按需要重建兼容 facade。
- 每个阶段都可以先与旧实现并行存在，使用测试锁住行为后再删除旧路径。
- 新主线编排点可以沿用 `IrPipelineCompiler` 这个概念，但应在 `src/main/java` 中重新实现为显式 stage pipeline。
- README 只保留用户视角；内部设计、rewrite 计划和扩展指南放在 `docs/`。

## 当前风险点

- `MethodIrBuilder` 过大，职责交织，后续 opcode 支持会越来越难读。
- 没有独立 class hierarchy / call graph，virtual/interface call 只能走保守 runtime helper 路径。
- SSA block parameter 模型已进入主线；后续风险转为扩展 dominance 校验、exception/finally 完整路径和更完整 frame type lattice。
- 前端 skip、runtime helper、LLVM emission 之间存在隐式耦合，新增 lowering 时容易漏测 runtime/backend 行为。
- method rewrite、native registration、fallback blob 和 JAR repackaging 若不集中在 packaging 阶段，会重新变成跨阶段隐式耦合。

## 长期完成标准

- 新人能从 `docs/rewrite-roadmap.md` 和 `docs/pipeline/README.md` 理解整个编译管线。
- 每个 pipeline stage 有独立模型、builder、validator 或 focused tests。
- 增加 bytecode opcode / Java feature / runtime lowering 时，有明确推荐路径和测试落点。
- `IrPipelineCompiler` 的主流程能像路线图一样从上到下阅读。
