# j2ll Rewrite Roadmap

Native 防逆向不以“反编译结果看起来很乱”为验收标准。实战攻击基线与当前逐项实施清单见
[`native-hardening-attacker-validation.md`](native-hardening-attacker-validation.md)。

本文档描述 j2ll 后续 rewrite 的目标管线、模块边界、实施阶段和完成标准。当前方向是：保留旧代码作为 legacy reference 和行为样本，但主线从干净架构重新实现，避免继续被旧的职责混杂方式牵引。新实现直接使用根目录 `src/main/java` 和 `src/test/java`，旧 `obfuscator/src` 不再承载新代码。

## 总目标

产品目标：j2ll 是 JVM-hosted JAR 混淆 / native lowering 工具。输出产物仍然是可运行 JAR，并在 Java 17 或更新 JVM 中通过唯一 `<embeddedLibraryDirectory>/Loader.class`、embedded native library、JNI / `RegisterNatives` 和 runtime helper 执行被 lower 的方法。

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
- 遇到不确定语义时保守地把整个 selected method 标记为 `skipped`，保留其原 Java body；不静默生成可能错误的 native code，也不复制/嵌入原 method bytecode。
- 每个 stage 都应有 validator、diagnostic 或 focused tests，不能把正确性压力推给最终 LLVM/backend。

## 健壮性加强方向

当前路线需要补强这些层，让 JVM-hosted lowering 像成熟 compiler pipeline 一样可验证、可回退、可观测：

- 字节码语义模型：明确 JVM stack map frame、category-1/category-2 value、monitor、exception、class init、array store check、null check、numeric conversion、invokedynamic 等语义。
- Classpath/world model：区分完整 classpath、partial world、JDK external world、unknown dynamic loading，并让每个分析知道自己的精度边界；这里的 world model 只服务 JVM-hosted analysis / obfuscation。
- 分层 native 支持：能由已验证 JNI/runtime helper 保持语义的调用仍属于 `nativeLowered`；当 hierarchy、RTA、points-to 或 devirtualization 的不确定性无法由这种 helper path 表达时，跳过整个 selected method，而不是猜测。
- Stage validator：parse、CFG、hierarchy、call graph、SSA、optimization、LLVM emission 前后都要有可测试的不变量。
- Canonical IR：在优化前把 IR 规整到少数稳定形态，降低 pass 和 backend 复杂度。
- Deoptimization / runtime guard 预留：如果未来做激进优化，应预留 guard/slow-path 表达能力。slow path 必须是显式 native/JNI helper 语义，不能恢复已经删除的 embedded bytecode fallback。第一版可以不实现 deopt，但 IR 设计不要封死。
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

xyz.melodysky.analysis.field
  FieldUseAnalyzer
  FieldUseIndex
  NativeFieldInternalizationPlanner
  NativeFieldInternalizationPlan

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
  RuntimeHelperDeclarationEmitter
  RuntimeStubGenerator

xyz.melodysky.packaging
  Repackager
  MethodRewritePlanner
  RuntimeLoaderPlan
  NativeLoaderClassGenerator
  RuntimeLoaderCollisionValidator
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
- selected method 的 unsupported 结果统一写入 `reports/skipped-method-report.json`，并遵守 `nativeLowered` / `skipped` 双状态契约。

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
- e2e 测试覆盖至少一个 virtual call 被 devirtualize 的样例，以及一个无法安全 native lowering 而稳定 `skipped` 的样例。

当前实现状态：

- 已有 per-class `LlvmModule` / `.ll` emission，默认 Java method function 为 `internal hidden`。
- `LLVM_NATIVE_PATH` 已接实真实 native lowering：ordinary static/instance primitive/reference-handle method 从 Bytecode -> SSA IR -> per-class LLVM module / `.ll` -> hidden linkable LLVM function -> JNI wrapper -> `RegisterNatives`。六个固定目标都进入真实动态库构建；output JAR child JVM E2E 当前在 host target 上验证。
- 当前 `LLVM_NATIVE_PATH` 覆盖 primitive/reference-handle method、分支/phi/switch、field/array/allocation/type helper、monitor/synchronized、显式 throw、reflection、bounded Unsafe/VarHandle、String/StringBuilder/StringConcat、常见 LambdaMetafactory/MethodHandle、Math/boxing/Objects、env-backed `Object.getClass()` / `Class.getClassLoader()`、JVM-backed `Thread.sleep(J)V`、same-class direct call 和受限 virtual/interface/default-interface JNI dispatch。逐descriptor审核的resource/InputStream/ByteBuffer/byte-array fill/Throwable suppression/hidden-class lookup调用复用tokenized JVM bridge，不新增native object model或Loader class-definition API。通过已验证 JNI/runtime helper 执行 Java/JDK 语义仍是 `nativeLowered`，不会复制 selected caller 的原 method bytecode。
- 受保护的JNI/runtime helper instruction现在可进入native exception CFG：SSA保存pending exception与throw-site locals，LLVM在site后检查/清除pending state，按classfile handler顺序做typed/catch-all dispatch，未匹配时恢复并rethrow。显式`athrow`复用有序dispatch。无法得到一致throw-site frame、不可约exception state或复杂monitor/finally interaction的shape仍保守`skipped`。
- 普通constructor的受限实现保留从入口到真实 `this(...)` / `super(...)` 调用的线性verifier prefix及原实参计算，post-init IR进入`LLVM_NATIVE_PATH` body helper；`<clinit>`保留loader/bootstrap stub并把完整可支持IR交给native helper。`TEMPLATE_JNI_PATH`继续覆盖其他语义完整的生成式String/array/exception/interface bridge。无法由 LLVM、生成式 template 或批准的 JNI/runtime helper 完整表达的 selected method 统一为 `skipped`，保留原 Java body、不进入 `RegisterNatives`。
- Bytecode-preserving helper class/blob/carrier/decoder与中间态 method outcome已退出目标架构。后续功能路线以扩大 `nativeLowered` 的 helper/backend shape、逐步减少 `skipped` 为主。
- 为了 C wrapper 跨 object 链接，native build artifact 使用 `external hidden` LLVM function；默认 debug dump 仍保持 `internal hidden`。symbol audit 必须确认 LLVM implementation symbol 不导出。
- 上述protected exception与initializer路径已有focused tests和Windows real-Zig host child-JVM differential；`Object.getClass()`与`Thread.sleep(J)V`目前有focused planner/LLVM/C ABI evidence。六目标构建仍只证明跨目标产物、格式、架构与export closure，不扩大宣称为non-host JVM runtime E2E。

### Phase 8：Protection / Obfuscation 分层落地

目标：

- 把 SSA IR protection、LLVM module model protection 和 binary symbol hardening 分开实现。
- 默认开启已实现 protection pass；未实现 pass warning + ignore；单 method 不适用时只跳过该 pass。

建议迁移：

- binary hidden LLVM linkage 和 final export allowlist audit 保持不可关闭基线；strip/remove-PDB 等额外 release hygiene 仍与 IR/LLVM pass 分层。
- 已实现的字符串/常量加密、独立 basic-block splitting、独立 fake branches、block-name obfuscation、保守 control-flow flattening、LLVM name obfuscation 和 LLVM indirect calls 继续按独立 pass 验证。
- 原 implementation checklist 的 8 项已按职责进入 program/IR、LLVM model、packaging/native registration 和 field-analysis pipeline，并已有通过的 Windows real-Zig host child-JVM 与六目标 feature-specific structural evidence；后续按同一 checklist 收口更广的 host boundary、optimized machine-code retention 和 non-host runtime evidence，不把 cross-link 扩大成目标 OS/JVM 已运行。

完成标准：

- 每个 protection pass 有 deterministic seed test、disabled no-op test、validator test。
- symbol audit 能验证 Java method internal symbol 不导出。
- protection report 能说明 pass 是否运行、跳过或尚未实现。

当前 v1 状态：

- 已接实 IR `StringEncryptionPass`、`PrimitiveConstantEncryptionPass`、`ControlFlowFlatteningPass`、独立 `BasicBlockSplittingPass`、独立 `FakeBranchesPass` 和 `BlockNameObfuscationPass`，并在 all-on JVM-hosted E2E 中验证不破坏现有 native lowering vertical slice。`fakeBranches` 与 `basicBlockSplitting` 是不同的必填 boolean，`blockNameObfuscation` 是新增必填 IR boolean；三者产生独立 pass/report row。`FakeBranchesPass` 会真实改写 protected IR，但当 method 没有可用动态参数而使用常量 predicate fallback 时，managed Zig `ReleaseSafe` 可能将该 branch 优化消除，不能宣称最终 binary 一定保留。`PrimitiveConstantEncryptionPass` 现在覆盖 `int` / `long` XOR split，以及 `float` / `double` raw-bit XOR + LLVM bitcast，包含 `NaN`、`-0.0` 和 infinity 的 child JVM raw-bit parity；`ControlFlowFlatteningPass` 当前覆盖安全 simple branch shape，复杂 shape 记录 `CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE`。支持 shape 的 block-to-state/default target 使用 per-build/per-method dense permutation，状态集合仍为 `[0, blockCount)`，不引入额外 table 或 transition cost。
- Program-level `MethodInliningPass`、`MethodSplittingPass` 和 IR `callIndirection` 已由 `ProgramIrProtectionCoordinator` 接入。Inlining 只接受小型 pure-scalar static/private-self callee；splitting 只 outline single-block scalar suffix 到 compiler-internal helper；IR call plan 当前只接受 module-local same-owner static/private-special call，并由 backend 生成 internal `j2ll_ircit_*` table，virtual/interface 与 cross-owner direct call 在 backend 支持前以稳定 reason fail closed。三者都有 validator/fail-closed report；Windows real-Zig host E2E 已断言 pass-RAN、output parity、异常传播、outlined actual symbol、IR table retention 和仅 loader roots 导出，六目标专项已断言共享 LLVM 进入每个 target graph、六库非空、privacy 与精确 export。ReleaseSafe 后 outlined/table 的稳定 machine-code retention 仍不是当前保证。
- `fieldInternalization` 已与真实实现同步进入 schema，默认 `false`，在 declared `CLOSED_WORLD`，或 build-time Y 明确授权的 current-JAR-only scope 下处理 input-base `private static` `boolean/byte/short/char/int/long/float/double` 与 JVM reference/array、same-owner static accessor 且所有 final access 都为 `LLVM_NATIVE_PATH` 的严格子集。current-JAR-only 是 feature-scoped invocation decision，不改写 `worldModel`、不读取配置 classpath，N/EOF fail closed，validate/dry-run 只报告待确认 warning。primitive 使用 per-defining-`jclass` weak-keyed relaxed atomic raw-bit state，窄整数遵循 JVM descriptor 截断/扩展，float/double 通过 LLVM bitcast 保留 raw bits；reference/array 值由唯一 `Loader.class` 的 per-defining-Class `ClassValue<Object[]>` sidecar 在 JVM heap 强持有，每个 native function activation 只在首次实际访问时惰性获取一次 JNI local ref，用 native stack cache cell 复用并在退出时释放，不建立 native strong global ref。final-plan validation、FieldNode removal 和 residual-reference audit 共用同一批准计划；gated host E2E 已覆盖全部类型边界值、NaN payload/negative zero、Object identity/null/GC strong hold、并发与双 ClassLoader，六目标专项已覆盖真实 slot call/storage/sidecar build-graph、privacy 与 export，non-host runtime 与 deterministic real-Zig dual-run仍待补。
- `methodTableHiding` 已实现为 final native-registration plan：per-owner physical registration order 由 build identity 派生，generated C metadata encoded at rest，并只在 owner registration window 内以 straight-line assignment 构造临时 `JNINativeMethod[]`；final native 不保留 token/function arrays 或 runtime join。method name/descriptor 同值只在 owner-local、purpose-local 范围复用。常规 `<=64` bindings 且去重 text scratch `<=16 KiB` 的 owner 使用有界栈 storage，超限 owner 使用 heap；两条路径都清零表与明文 scratch。运行时仍调用 `RegisterNatives`。gated host E2E 已覆盖多 method/双 ClassLoader；六目标专项已覆盖 two-owner transient registration build-graph、privacy 与 export，多 owner + virtual/interface 的真实 runtime 和 non-host runtime仍待补。
- 通用 generated-C sensitive literal 已改为真实 use-site 惰性解码：同一 C function 内同明文共享一个 activation-local slot并在单次 activation 最多解码一次，不跨 function 共享明文 cache/encoding；函数内聚合 scratch 由统一 cleanup hook 覆盖所有退出。该优化不恢复 global decoder 或集中 text directory。
- LLVM `LlvmBlockLayoutPerturbationPass`、`LlvmOpaquePredicatePass` 和 `LlvmGlobalLayoutPass` 已接入 validated module model。当前分别只改变 non-entry block emission order、给 conditional branch 加 defined-integer 恒真 gate、重排 module-local global slots；Windows real-Zig host E2E 已断言三项 `RAN`、parity、LLVM marker/global retention 和 export baseline，六目标专项已验证共享 transformed LLVM 进入每个 target graph并完成六库 privacy/export。optimizer/linker 仍可折叠或重新布局，不能宣称 final machine-code shape 稳定保留。
- LLVM name obfuscation 已改为共享 `LlvmNameMangler`，planner、LLVM module lowering、Zig workspace `.ll` 和 JNI wrapper C 使用同一 deterministic `j2ll_f_<sha256>` symbol；symbol audit 仍只允许 loader/bootstrap exports。
- LLVM `visibilityHardening` 已从 Config/schema 删除；Java implementation/protection symbol 的 hidden/internal linkage 和最终 export allowlist audit 仍是不可关闭基线。
- `reports/protection-report.json` 已记录上述 pass 的 `RAN` / `SKIPPED` / `FAILED` 与 hash-only seed identity；`field-internalization-report.json` 和 packaging method-table evidence 已进入 report index/readiness。单 method/module 不适用只记录稳定 reason，不改变 lowering status。

### Phase 9：Native Build / Link / Symbol Audit

目标：

- 通过 managed Zig `0.15.2` 为 selected target 生成动态库。
- 只导出 loader/bootstrap JNI wrapper 和必要 C ABI wrapper。
- 内部 Java method/native helper symbols 默认 hidden/internal。

建议迁移：

- 建立 `toolchain.NativeBuildPlanner`、target naming、workspace layout 和 managed `ZigToolchain` capability/preflight。
- `ZigToolchain` 固定解析可执行 `j2ll.jar` 同级的 `zig/zig(.exe)`；缺失或版本不匹配时，先查找同目录 Zig `0.15.2` archive，找不到再从 Zig 官方 download path 下载；local/downloaded archive 必须按内置官方 SHA-256 metadata 校验，通过后才解压并将官方目录内容规范化到 `zig/`；signature verification 当前明确报告 `notVerifiedBoundary`。
- `ZigBuildWriter` 生成统一 build plan，接收 per-class `.ll`、Zig-managed `.o`、JNI wrapper C 和 runtime helper C；最终 source set 不得包含 fallback blob carrier 或原 method bytecode 副本。
- `.ll` 到 target object、`.o` link、动态库命名、target triple、export list、strip/remove-PDB policy 都必须通过 Zig toolchain 编排。
- 建立 `toolchain.symbols` 的 export list、strip plan 和 platform policy。
- 让 symbol audit 成为成功输出的必经 validator。

完成标准：

- 每个 selected target 在扁平的 `native/<library-file-name>` 路径产出固定名称动态库；不同目标由文件名区分，不创建 per-target 子目录。
- Windows release artifact 不打包 PDB。
- `reports/symbol-audit.json` 记录 allowlist、actual exports 和 audit result。
- Release-readiness gate 写入 artifact audit、support/opcode matrix、known blockers、readiness result 和用户摘要 `reports/summary.json`；deterministic release suite 必须覆盖 minimal LLVM lowering、mixed LLVM/JNI-helper/protection path、明确 `skipped` boundary、config/signing/target/artifact-audit expected failure 和 realistic CLI/reflection/packaging samples。artifact audit 检查 output JAR/native resource hygiene、metadata/hash/export/PDB policy、每个 `nativeLowered` method 的实现与注册闭包、每个 `skipped` method 的原 body 保留且无 registration，以及最终 native/source/JAR 中不存在 fallback helper/blob/carrier/decoder。六目标结构性交叉构建与 target OS/JVM runtime evidence继续分开记录。

当前实现状态：

- 已接实 managed Zig `build.zig` 的六目标 dynamic-library build vertical slice。它通过 j2ll 生成的单一 Zig workspace 编排 JNI wrapper C、runtime helper C 和 per-class LLVM `.ll` input；`LLVM_NATIVE_PATH` 与 `TEMPLATE_JNI_PATH` 都只接受生成式 native/helper implementation，不接收 fallback carrier 或复制的 Java method bytecode。
- Production builder 从 final validated LLVM module model 的真实 symbol reference
  计算 host-JNI runtime source-family closure；declaration 不单独成为 root。未知
  `j2ll_rt_*` / `j2ll_h_*` 或不完整 evidence 会回退到保守全量，直接 generator
  API 也默认全量。该裁剪只移除已证明不可达的 runtime source，不改变 helper ABI。
  2026-07-28 的固定输入/seed Windows x64 单次 A/B 中，generated C / DLL /
  `.text` raw / output JAR 分别减少 18.665% / 28.121% / 30.745% / 2.191%；
  57 `nativeLowered`、14 `skipped` 与 artifact/readiness 结果保持一致。单次
  wall-clock 的 -9.383% 只作方向性证据。
- selected target matrix 的 build plan、`build.zig`、artifact layout、target preflight、format-aware symbol audit 和 packaging report 已稳定。一次 matrix-wide `zig build` 可实际生成 Windows GNU x86_64/AArch64、Linux GNU glibc 2.17 x86_64/AArch64、macOS 10.15 x86_64/11.0 AArch64 的 DLL/SO/dylib；support matrix 以 `ZIG_CROSS_TARGET_SUPPORTED` 记录这层结构性证据。schema v1 selected target 默认 required；实际 capability/preflight/compile/link 不可构建的 target 才进入 `failedTargets`，diagnostic 使用 `ZIG_TARGET_UNBUILDABLE`，记录 required/currentHost/buildable、Zig target query、expected artifact path/name、failure kind、required capability、platform SDK requirement 和 build log tail，并使 pipeline failed 且不写 final output JAR。非 host runtime child-JVM E2E 仍待在对应 OS/JVM 上补证据，并以 `CROSS_TARGET_RUNTIME_E2E_PENDING` 单独跟踪；后续 Phase 9 工作应继续扩 runtime validation 和 platform-specific hardening evidence。

### Phase 10：Packaging / Loader / Native Registration

目标：

- 输出仍然是可运行 JAR。
- 原 method body rewrite、native registration、embedded libraries 和 skipped-method preservation audit 都由 packaging 阶段统一落地。

建议迁移：

- 普通 class method 使用 `nativeOriginal`：原方法去 Code、加 `ACC_NATIVE`、用 `RegisterNatives` 绑定。
- `<init>` 使用合法 constructor stub + native body helper。
- `<clinit>` 使用 loader/bootstrap stub + native body helper。
- 有 Code 的 interface method 使用 interface method stub + generated native helper。
- selector 命中的 abstract、already-native、无 Code interface declaration/annotation element只记录 eligibility evidence，无 method status且不触发 confirmation。Selected Code-bearing method无法安全生成 native implementation时记录 `skipped` + 稳定 reason，保留原 body且不注册；base method有 multi-release counterpart时也按此规则进入 gate。
- final implementation plan 形成后、创建 Zig workspace 前，CLI 通过独立的 `SkippedMethodCollector` / `SkippedMethodConfirmation` 组件按稳定顺序打印每个 skipped method 与 reason，并明确提示其原 Java bytecode 会保留；只有显式 `Y` 继续，`N`/EOF 终止。`--validate` / `--dry-run` 不读取 stdin；dry-run 明确记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 和 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。
- schema v1 不提供 `requiredNative`；是否接受 skipped methods 由每次 default build 的确认 gate 决定。

完成标准：

- output JAR 保留 manifest、resources、services、module-info、multi-release metadata。
- output JAR 在配置的 `embeddedLibraryDirectory` 下包含 selected target 动态库。
- output JAR 只包含一个 Java 17 `<embeddedLibraryDirectory>/Loader.class`；它只承载动态库选择/校验/加载/注册，以及 field internalization 实际需要时的 `ClassValue<Object[]>` sidecar；不存在 class-definition/blob-decoding API。
- `embeddedLibraryDirectory` 是规范 Java internal package path；输入 base/MR 同名 Loader 在 Zig 前以稳定碰撞诊断失败。旧 runtime support classes和 artifact-specific native loader不再输出。
- packaging report 能列出 generated loader、rewritten classes、registration summary 和 skipped-method preservation evidence。

当前实现状态：

- 当前 host E2E 已能在 child JVM 中运行 output JAR，并通过唯一 Loader、SHA-256 校验、`System.load`、`JNI_OnLoad` 和 `RegisterNatives` 绑定 native methods；embedded dynamic library 来自 managed Zig `build.zig` workspace。Loader name 由目录决定，因此同一 defining `ClassLoader` 中不同产物复用相同目录仍是明确已知边界，建议应用使用唯一目录；独立 ClassLoader state 仍隔离。
- report 已记录唯一 generated loader、rewritten methods、embedded library path/SHA、registered native methods、registration groups、exported symbols、JAR preservation summary、signature action、每个 selected target 的 expected artifact path/name、每个实际 built target 的 SHA-256/exported symbols、failed target reason、required capability、platform SDK requirement、failure kind、build log tail、Windows PDB exclusion policy、final JAR `META-INF/j2ll/*` metadata、`reports/artifact-audit.json` / `reports/support-matrix.json` / `reports/opcode-support-matrix.json` / `reports/known-blockers.json` / `reports/release-readiness.json` release-readiness artifacts，以及每个 lowered method 的 `nativeImplementationPath`。failure runs 写入 `reports/failure-report.json` 且 `finalArtifactWritten=false`。protection report 记录 hash-only `sensitivePlaintextFacts` 的 `pathKind/gateMode/sourceSurface/reason`，artifact audit 自动消费当前已接实 deny-list facts，并报告 observed-only facts 和 surface coverage。release suite 测试还写入 `reports/release-suite-summary.json`，记录 suite profile、required/missing categories、suite/case metadata、aggregate、determinism evidence、expected support statuses、expected support evidence/report location、original/output child JVM result 或 config/toolchain/artifact expected failure null run、produced report paths、diagnostics、signature policy、protection variant、expected failure stage/reason 和 final artifact state。
- `reports/field-internalization-report.json` 现为 success/failure/readiness 必需证据，使用 hash-only field identity 记录 storage/lifecycle/final-path/removal decision；artifact audit 会阻断批准字段的残留 declaration/FieldInsn/Handle/bootstrap reference。`packaging-report.json` 的 method-table object 只写 opaque plan id、owner hash、binding count/token，不泄漏 raw mapping。
- 当前Windows real-Zig host E2E覆盖 `LLVM_NATIVE_PATH` 的 primitive/control-flow/field/array/allocation/type/String/reflection/Unsafe/VarHandle/monitor/call、protected pending-exception dispatch，以及合法constructor post-init和class-initializer native body helper；`Object.getClass()`与`Thread.sleep(J)V`当前只有focused planner/LLVM/C ABI evidence。`TEMPLATE_JNI_PATH`继续覆盖语义完整的生成式bridge。unsupported default-interface super、无法形成一致throw-site frame的exception shape、复杂monitor/finally、raw memory 与其他未实现 shape 以 `skipped` no-rewrite boundary 覆盖。
- Config/CLI 当前已有 `docs/config.schema.json`、`docs/examples/*.json` 和 flag-based `j2ll [--config <config.json>] [--validate|--dry-run] [--debug]`；config 缺省为当前目录 `Config.json`，mode 缺省为 build。Validate 不建 workspace；dry-run/build 自动在 resolved `outputDirectory` 下创建 `build_yyyy-MM-dd_HH-mm-ss[-n]`，成功 JAR 直接写在 workspace 根。CLI 保留短 stdout/stderr、exit code mapping 和 `summaryReport=...` 输出。Intermediate artifact 当前写 `intermediates/intermediates-manifest.json`，记录 config switches、class/method artifact ids 和实际 intermediate 文件 SHA-256；`--debug` 为本次运行开启 debug dump / per-class IR / LLVM / C，但不启用 native debug symbols。
- Interface method declaration stubs、full object/reference LLVM semantics、非线性constructor pre-init prefix或当前含任意exception table的constructor、无法形成一致throw-site frame/block arguments的exception state、复杂monitor/finally interaction、reflection shapes beyond the JVM bridge matrix、wait/notify native helper、broader virtual/interface dispatch helper implementation和非当前 host target 的 runtime E2E 仍是后续扩展项。路线优先级是逐项把这些 `skipped` reason 提升为真实 `nativeLowered`。

### Phase 11：Config / Reports / Artifact Contract 固化

目标：

- `config.json` schema、unknown field 策略、selector grammar、report JSON schema 和 artifact naming 都稳定下来。
- 缺少 required field 直接 preflight error；未知字段 warning 但不参与 resolved config。

建议迁移：

- 实现 `schemaVersion`、`worldModel`、signature policy 和 protection config。
- selector grammar 支持 class/method exact selector 和 class wildcard selector。
- per-class intermediate artifact 使用 safe class path + SHA-256 prefix；method id 使用 descriptor hash。

完成标准：

- `config.resolved.json`、diagnostics、skipped-method、lowering、field-internalization、packaging、protection、symbol-audit、support/opcode matrix、known blockers、release-readiness reports 和 release suite summary 都有 golden/focused test。
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
- method rewrite、native registration、skipped-body preservation audit 和 JAR repackaging若不集中在 packaging 阶段，会重新变成跨阶段隐式耦合。

## 长期完成标准

- 新人能从 `docs/rewrite-roadmap.md` 和 `docs/pipeline/README.md` 理解整个编译管线。
- 每个 pipeline stage 有独立模型、builder、validator 或 focused tests。
- 增加 bytecode opcode / Java feature / runtime lowering 时，有明确推荐路径和测试落点。
- `IrPipelineCompiler` 的主流程能像路线图一样从上到下阅读。
