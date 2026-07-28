# Protection Implementation And Evidence Checklist

本文档跟踪 8 个后补 protection work item 的实现与验收证据。它不再是“未实现字段清单”：当前代码已经把这 8 项全部接入对应的 program/IR/LLVM/native pipeline，`ProtectionAvailabilityReporter.currentImplementation()` 不再为它们产生 `PROTECTION_PASS_NOT_IMPLEMENTED`。

“已经接线”不等于“所有发布证据已经闭环”。本清单明确区分：

- **实现已接线**：Config -> analysis/plan -> validated model rewrite -> backend/toolchain/packaging 的真实路径存在。
- **host runtime evidence**：使用真实 Zig 构建 host 动态库，并在 child JVM 中比较 original/output。
- **六目标 structural evidence**：同一次 matrix-wide Zig invocation 产出六目标动态库，并做 feature-specific build-graph/content/privacy/export audit。
- **non-host runtime evidence**：在目标 OS/JVM 上实际运行 output JAR；它不能由 cross-link 成功替代。

## 方法覆盖基线

本清单中的 protection pass 只作用于最终可执行的 native implementation，不改变统一方法覆盖契约：

- selector 命中且有 Code 的 method 最终只能是 `nativeLowered` 或 `skipped`。
- JVM/JNI runtime helper 是 native implementation 的组成部分；完整 helper-backed path 仍是 `nativeLowered`。
- 任一 stage 无法完整保持方法语义时，整 method `skipped`，原 Code 保留在 owner class 中，不生成 native rewrite、helper body 或 `RegisterNatives` binding。
- no-Code selector match 是单独 eligibility evidence，不触发 skipped-method confirmation。
- 不复制、编码或嵌入可执行的原 class/method Code，也不生成其 carrier、decoder 或 hidden-class definition path。
- schema 不增加 `requiredNative`。默认 build 在 final plan 后、任何 Zig workspace/invocation 前稳定列出全部 skipped method 与 reason，并询问 `continue? (Y/N)`；仅显式 `Y` 继续，`N`/EOF 终止。`--validate`/`--dry-run` 不读 stdin，也不形成 final skipped set；dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 与 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。

下文 protection report 的 pass status `RAN` / `SKIPPED` / `FAILED` 是 pass-level applicability evidence，不是 method lowering status。

截至当前代码快照：

| Layer | Work item | Implementation | Current evidence gap |
| --- | --- | --- | --- |
| IR | `methodInlining` | 已接线，严格 pure-scalar direct-call 子集 | Windows host runtime 与六目标 feature-specific structural evidence 已通过；non-host runtime 待补 |
| IR | `methodSplitting` | 已接线，单 block suffix / 单 scalar live-out outline 子集 | Windows host actual-symbol/export 与六目标 structural evidence 已通过；non-host runtime 待补 |
| IR | `callIndirection` | 已接线，当前限 module-local same-owner static/private-special call + hidden LLVM table | Windows host static int/long、异常传播与六目标 table-retention evidence 已通过；更广 dispatch/cross-owner backend/runtime 待补 |
| Packaging/native registration | `methodTableHiding` | 已接线，owner-local transient straight-line table + encoded-at-rest JNI metadata | host 注册/双 ClassLoader 与六目标 multi-owner structural evidence 已通过；virtual/interface 与 non-host runtime 待补 |
| Program / IR | `fieldInternalization` | 已进入 Config/schema，默认 `false`；严格 `private static` `Z/B/S/C/I/J/F/D`、`L...;` 和 `[...]` 子集已接线 | host 全类型边界、Object identity/null/GC strong-hold、并发/双 ClassLoader 与六目标 primitive/ClassValue storage/privacy evidence 已通过；non-host runtime 待补 |
| LLVM | `opaquePredicates` | 已接线，validated conditional-branch model 子集 | Windows host 与六目标 emitted-IR/build-graph evidence 已通过；ReleaseSafe 仍可能折叠当前恒真 predicate |
| LLVM | `blockLayoutPerturbation` | 已接线，只改变 non-entry block emission order | Windows host 与六目标 build-graph/export evidence 已通过；不保证 linker 后的最终 machine-code layout |
| LLVM | `globalLayout` | 已接线，只重排 module-local LLVM global emission slots | Windows host 与六目标 global-retention/privacy/export evidence 已通过；non-host runtime 待补 |

`fakeBranches`、`basicBlockSplitting` 和 `blockNameObfuscation` 已分别接入独立 IR pass，因此不在这 8 项中。`visibilityHardening` 也不在清单中：该字段已从 Config/schema 删除，hidden LLVM linkage 和 final export allowlist audit 是不可关闭的 JVM-hosted baseline。

## 通用完成门槛

| Gate | Status | Evidence / remaining work |
| --- | --- | --- |
| Plan/model-driven，不以 ASM 或 `.ll` 文本替换绕过 stage | 已满足 | program coordinator、field plan、LLVM module passes、registration plan 都消费显式模型 |
| Boolean `false` 为 no-op，`true` 进入真实实现 | 已满足 | 8 项均已列入 current implementation；`fieldInternalization` 与实现同步进入 schema，默认关闭 |
| Build identity 域分离 token/order | 进行中 | 默认 build root 随机；显式 seed 模式覆盖同 seed 稳定性，报告只写 mode 与 context-bound hash |
| pass 前后 validator / fail-closed rollback | 已满足 | IR、LLVM、field final plan、registration-plan matching 和 packaging transform 都有 validator/fail-closed 边界 |
| focused unit/golden tests | 已满足 | 8 项均有适用、不适用或 disabled/invalid shape 的 focused coverage |
| main pipeline wiring | 已满足 | `NativeLlvmCompiler` 只编译 final LLVM implementation/helper closure；reports、intermediates 与 Zig 共用同一 final module result，generated C 与 field transform 由后续对应 stage 消费 |
| pass-specific host child-JVM E2E | 已满足（Windows host） | `ProgramProtectionNativeRuntimeE2eTest` 覆盖 program/IR/LLVM 六项，`ProtectionStateNativeRuntimeE2eTest` 覆盖 field/method-table；两项 gated real-Zig tests 均已实际通过 |
| 六目标 feature-specific compile/link/content/privacy/export audit | 已满足 | `ProtectionCrossTargetEvidenceTest` 让共享 LLVM/C sources 分别进入六个 target graph，验证六个非空 artifact、目标格式/架构、仅 `JNI_OnLoad` 导出（平台固有 runtime 符号单独容忍）及 UTF-8/UTF-16 raw identity absence |
| non-host OS/JVM runtime E2E | 待补 | 所有 8 项都仍受 `CROSS_TARGET_RUNTIME_E2E_PENDING` 边界约束 |
| stable protection/packaging/audit evidence | 已满足当前 v1 范围 | 各 pass 已写 `RAN`/`SKIPPED`/`FAILED`；field 有独立 report，method-table 有 packaging hash-only evidence；host 与六目标专项测试消费这些证据 |

六目标专项证明 transformed LLVM/generated C 被列入每个 target 的真实 Zig build graph，并验证最终动态库、架构、export allowlist 与静态 plaintext privacy；它不声称在 stripped/optimized machine code 中还能按名字找到 outlined helper、IR call table 或 opaque branch。`ReleaseSafe`/linker 可 inline、fold 或重排这些形态，non-host runtime 也仍需在目标 OS/JVM 上单独执行。

`NativeLlvmCompilerTest` 另用 LLVM + template 混合 implementation plan 锁定 final closure：template-only method 不进入 module、pass affected symbol 或 Zig 输入；同一 authoritative compilation 被主线报告、debug intermediates 和 native source writer 复用，避免证据与实际构建漂移。

## IR `methodInlining`

当前实现是受限但真实的 program-level SSA inline：

- `ProgramIrProtectionCoordinator` 从 parsed access facts、reflection plan 和 preliminary native implementation plan 生成候选。
- 当前只接受 `CALL_STATIC`，以及 same-owner private `CALL_SPECIAL` 且 receiver 可证明为 `self` 的调用。
- caller/callee 都必须是 preliminary `LLVM_NATIVE_PATH`，callee 必须是单目标、非 recursive、非 reflection/unsupported-boundary-sensitive。
- callee 仅允许无 exception/monitor/JMM/call/field/helper side effect 的 pure scalar IR；默认上限为 24 条指令、每 caller 8 个 site。
- frontend direct call 的无 handler pending-exception evidence 不是永久禁止 inline 的理由：callee 已证明为 pure、site 没有 handler，且 synthetic exception value 无任何 use 时才可随 call 一起删除；protected edge、specific exception kind 或 observable exception value 仍 fail closed。
- 支持 block/value remap 和多个 return 汇入 typed continuation；失败时整次 site rewrite 回滚。
- 原 Java method、reflection-visible method 集和仍需的 native registration 不会因 inline 被删除。

报告使用 `METHOD_INLINING`，并区分 `METHOD_INLINING_NO_CANDIDATE`、`METHOD_INLINING_RECURSIVE`、`METHOD_INLINING_REFLECTION_SENSITIVE`、`METHOD_INLINING_EXCEPTION_SENSITIVE`、`METHOD_INLINING_MONITOR_JMM_SENSITIVE`、`METHOD_INLINING_VALIDATION_FAILED` 等原因。

已完成：

- [x] straight-line static、private-self special、multiple return、block parameter/value remap focused tests。
- [x] recursion、reflection、skipped boundary、exception、monitor/JMM、oversized/invalid shape 的拒绝测试；另覆盖 frontend-style unprotected pending-exception evidence 的安全删除与仍有 use 时的拒绝。
- [x] disabled no-op 与 seed-deterministic generated names。
- [x] mainline program coordinator 接线，pass 不再 warning + ignore。
- [x] `ProgramProtectionNativeRuntimeE2eTest` 已在 Windows real-Zig host 断言 `METHOD_INLINING=RAN`、original/output parity、raw method identity 不在 native binary，且动态导出只有 `JNI_OnLoad`。
- [x] `ProtectionCrossTargetEvidenceTest` 已验证共享 transformed LLVM 进入六目标 build graph、六库非空、仅 `JNI_OnLoad` 导出（平台固有 runtime 符号单独容忍）和 raw identity absence。

## IR `methodSplitting`

当前实现是 compiler-internal outline，不新增 Java method：

- 只从没有 exception/helper-sensitive operation 的 ordinary `LLVM_NATIVE_PATH` method 中选择单个 basic block 的 suffix。
- region 只允许 native scalar 指令，live-in 必须是 scalar 且在 split point 可用，当前只允许一个 scalar live-out。
- 原 terminator 和多个 successor 仍留在 caller；helper 只返回 live-out。
- outlined helper 不进入 `IrClass` 的 Java method 集、不进入 packaging/`RegisterNatives`；它作为 compiler-internal IR 进入 LLVM/native planner。
- v1 outlined helper 只允许 pure-scalar body，不能再调用第二层 method/runtime helper；`NativeLlvmCompiler` 会对此类嵌套 call fail closed，避免在未建模传递 ABI closure 时生成错误调用约定。
- helper 的实际 emitted symbol 必须通过同一个 `LlvmNameMangler` 解析；plan 内的 `j2ll_oh_*` token 不是最终 symbol。
- caller/helper 作为原子结果校验；validator failure 时不留下 partial helper。

报告使用 `METHOD_SPLITTING`，并区分 `METHOD_SPLITTING_NO_SAFE_REGION`、`METHOD_SPLITTING_UNSUPPORTED_LIVE_OUT_ARITY`、`METHOD_SPLITTING_EXCEPTION_SENSITIVE`、`METHOD_SPLITTING_HELPER_SENSITIVE` 和 `METHOD_SPLITTING_VALIDATION_FAILED`。

已完成：

- [x] split-point、live-in/live-out ABI、caller successor、compiler-internal helper focused tests。
- [x] helper-sensitive、multiple-live-out、stub-backed、invalid method 拒绝测试。
- [x] compiler-internal helper nested-call fail-closed 测试，防止未来放宽 split shape 时漏算 JNI/owner ABI closure。
- [x] disabled no-op、seed determinism 和 actual LLVM symbol resolver 覆盖。
- [x] compiler-internal helpers 进入最终 native implementation planning。
- [x] 同一 Windows real-Zig host E2E 已断言 `METHOD_SPLITTING=RAN`、outlined helper 的实际 mangled symbol 存在于 emitted LLVM，且未进入动态导出。
- [x] 六目标专项测试已验证 outlined actual symbol 保留在共享 emitted LLVM、LLVM source 进入每个 target graph 且六库仅导出 loader roots。

## IR `callIndirection`

IR 与 LLVM `indirectCalls` 现在是两个独立层：

- IR planner 当前只接受 caller/target 都有最终 native 候选证据、且 target 与 caller 位于同一 owner LLVM module 的 bytecode-direct static/private-special call。
- virtual/interface（即使 call graph 已证明 single target）和 cross-owner static/special 当前都以 `IR_CALL_INDIRECTION_BACKEND_UNSUPPORTED_SHAPE` fail closed；它们仍走原 JNI dispatch/static bridge，不伪造最终已间接化。
- unresolved/multi-target、dynamic/helper、skipped callee、constructor/class-initializer、signature mismatch 和非 native path 都稳定跳过该 protection candidate；若底层调用本身没有完整 native implementation，则由方法覆盖契约将 caller 记为 `skipped`。
- approved site 在 `IrInstruction` 上携带 typed `IrCallIndirectionRef`，由 IR validator 检查 plan/group/entry/signature/invoke kind。
- group identity 不只看 Java/SSA signature；planner 从 preliminary native implementation plan 取得每个 target 的 hidden `JNIEnv*` / owner-`jclass` ABI proof，并按 `IR signature + hidden ABI` 分组。validator 拒绝 forged mixed-ABI group，LLVM backend 仍独立核对真实 function-pointer type。
- `LlvmIrCallIndirectionPass` 只消费该 metadata，生成 internal `j2ll_ircit_<sha256>` pointer table；它不重新做 Java call resolution。
- 后续独立的 LLVM `indirectCalls` pass 不会重复改写已经变成 indirect LLVM call 的 site。

报告至少有两层证据：

- `IR_CALL_INDIRECTION`：Java-call-semantics plan/rewrite。
- `IR_CALL_INDIRECTION_BACKEND`：显式 IR plan 是否成功进入 hidden LLVM table。

已完成：

- [x] same-owner static/private-special、typed signature + hidden native ABI grouping，以及 virtual/interface/cross-owner backend-unsupported fail-closed focused tests。
- [x] unknown/multi-target/skipped/non-native/class-init-guard-missing 的稳定 pass-skip tests。
- [x] IR plan/method validator 与 forged/missing metadata failure tests。
- [x] backend hidden table model test，以及与 LLVM `indirectCalls` 不重复改写的实现边界。
- [x] Windows real-Zig host E2E 已覆盖 static `int`/`long` 间接调用、`IR_CALL_INDIRECTION` / `IR_CALL_INDIRECTION_BACKEND=RAN`、`j2ll_ircit_*` table retention、output parity 和除零 `ArithmeticException` 传播。
- [x] 六目标专项测试已验证 `j2ll_ircit_*` table 位于共享 emitted LLVM、该 LLVM 进入六个 target graph，且 table/internal implementation 不导出。

待补：

- [ ] 后续先实现能够保留 receiver null/dispatch 与 cross-owner class-init 语义的 backend 形态，再补对应 host E2E。

## Packaging/native registration `methodTableHiding`

该字段不是单 method IR rewrite。当前实现位于 registration plan 与 native C emission 边界：

- `MethodTableHidingPlanner` 按 registration owner 分组，为每个 binding 派生 build-scoped physical order；显式 seed 模式下可复现。collision-free 64-bit token 只写 hash-only report evidence，不进入 native runtime。
- 每个 owner 在自己的 registration window 内以 straight-line assignment 临时组装 `JNINativeMethod[]`，随后仍调用 JVM `RegisterNatives`，不改变真实 owner/name/descriptor 绑定。
- generated source 必须消费与 final `NativeRegistrationPlan` 精确一致的外部 plan；empty/mismatch fail closed。
- owner/name/descriptor 仍是 JNI 运行时必需信息，但最终 generated C 不再包含全局 metadata 目录、aggregate decode-all、persistent token/function arrays 或 nested join。每个 owner 独立保存 registration-domain、build-scoped encoded bytes，只在自己的注册窗口解码到临时 scratch，并在 `RegisterNatives` 的成功/失败路径清零临时文本与 `JNINativeMethod[]` 后释放。这是静态 at-rest obfuscation，不是运行时内存保密。
- aggregate root、per-owner helper、decode helper 和 implementation symbols 保持 internal/hidden；dynamic exports 只保留 JVM 必需的 `JNI_OnLoad`。owner name 在 lookup 后立即清零。rollback 同时检查 `UnregisterNatives` 返回值和 pending exception，exception restore 同时检查 `Throw` status与新的pending exception；任一证据不完整都通过编码诊断的 `FatalError` fail closed。

已完成：

- [x] report-token determinism、physical-order seed variation、collision-free evidence、disabled no-op tests。
- [x] multi-owner/multi-method generated-C integration、exact-plan matching、host C compile 与 fake-Zig export test。
- [x] gated real-Zig host child-JVM E2E 覆盖多 method 注册、静态 metadata 隐私与两个独立 ClassLoader。
- [x] `protection-report.json` 和 `packaging-report.json` 提供 hash/token-only plan/owner/binding evidence。
- [x] 六目标专项测试已验证 two-owner transient registration source 进入每个 target graph、无 token/function static arrays、六库 raw owner/member identity 不可见且只导出 `JNI_OnLoad`（平台固有 runtime 符号单独容忍）。

待补：

- [ ] host runtime 扩展到多 owner 与 virtual/interface dispatch registration。

## Program / IR `fieldInternalization`

`protection.ir.fieldInternalization` 已与真实实现一起进入 Config/schema，必填、默认 `false`。只有 protection root、IR root 和该字段都为 `true` 时启用；`CLOSED_WORLD` 直接满足 requirement，其他 world 在 build 时必须由用户 Y 明确授权 current-JAR-only scope。

这是会改变 Java reflection surface 的显式 opt-in：批准字段会从 output classfile 删除，因此 `Class.getDeclaredField(s)` 不再看到它。`CLOSED_WORLD` 是用户对完整 world 和无外部 observer 的声明；current-JAR-only 则是 feature-scoped、本次运行有效的风险接受，不改变 `worldModel`，明确排除配置 classpath 与 JAR 外 observer。N/EOF fail closed，validate/dry-run 只记录待确认 warning。

当前 v1 边界仍是严格 closed-world 子集：

- 只处理 input base class 中的 `private static boolean/byte/short/char/int/long/float/double` 与 JVM reference/array。
- 字段必须非 final/volatile/synthetic/enum-generated，无 ConstantValue、Signature、annotation/type-annotation；字段自身不能被 `<clinit>` 访问，owner 不能带 serialization 语义或 multi-release counterpart；无关 `<clinit>` 不做 owner-wide 阻断。
- 访问 method 当前还必须是 same-owner static method。primitive、reference 和 array access 都只接受 final `nativeLowered` method，且 final implementation 必须使用 field plan 认可的 native storage ABI；`skipped` 或普通 JVM field ABI accessor 一律拒绝 internalization。
- classpath access、cross-owner/nestmate access和 instance field 均拒绝。
- `FieldUseAnalyzer` 扫描 `FieldInsn`、LDC field Handle、invokedynamic/ConstantDynamic bootstrap arguments，并按 JVM field resolution 找实际 declaration。同 symbolic owner 的 unresolved field reference 使候选保守失效；current-JAR-only scope 仍忽略 owner 不属于 input JAR 的 unresolved external reference。
- owner-local reflection、Unsafe、VarHandle、MethodHandles Lookup field API、JNI/native loading、serialization 和 agent/instrumentation field-observer surface 使候选保留在 JVM。普通 MethodHandle invocation或单纯 dynamic class loading 不构成字段观察。
- LLVM approved primitive access 改写为 opaque native slot operation；approved reference/array access 通过 JNI wrapper 使用同一 `ClassValue<Object[]>` sidecar。final implementation plan 必须再次证明每个 accessor 是 `nativeLowered`、使用批准 storage ABI、读写次数匹配且 raw JVM field marker 已消失。
- primitive storage 以 defining `jclass` 的 `jweak` + `IsSameObject` 隔离 ClassLoader，失效 weak ref 在后续 lookup 中 lazy cleanup；slot 使用 `_Atomic uint64_t` relaxed raw-bit load/store，按 descriptor 实现 boolean low-bit、byte/short sign extension、char zero extension与 float/double bitcast。
- reference/array storage 始终位于 JVM heap：唯一 `Loader.class` 仅在需要时由 ASM injector 直接继承 `ClassValue`，per-defining-Class 缓存 `Object[]`。native activation 惰性获取并缓存 local sidecar ref，在返回/异常后释放。值访问走 JVM/JNI ObjectArray 语义，无 native strong global ref。
- packaging 只删除 final plan 批准且结构仍匹配的 field；artifact audit 再扫描 declaration、FieldInsn、Handle 和 bootstrap field reference。

独立 `reports/field-internalization-report.json` 记录 hash-only `fieldIdHash`、`INTERNALIZED`/`KEPT`、native slot、access methods、final implementation paths、storage/atomic/lifecycle policy、`removedFromOutputClass`、reason codes 和不伪装 closed world 的 `worldAnalysis` scope/authorization evidence。

已完成：

- [x] analyzer/planner 对 eligible 与 world/access/metadata/dynamic/MR/skipped-accessor 边界的 focused tests。
- [x] IR rewrite、LLVM native-slot lowering、C storage、final-plan validation tests。
- [x] packaging deletion、class verification、residual field-reference artifact audit tests。
- [x] report/index/readiness/failure evidence 接线。
- [x] gated real-Zig host child-JVM E2E 覆盖所有支持类型、窄整数边界、float/double NaN payload/negative zero raw bits、Object identity/null/GC strong hold、并发更新、field removal、两个独立 ClassLoader 的状态隔离，以及多个 native accessor 共享同一 reference sidecar 状态。
- [x] 六目标专项测试已验证每类 slot 的真实 call 位于共享 LLVM、primitive weak-keyed atomic storage 与 ClassValue/ObjectArray bridge 位于共享 generated C、两类 source 均进入每个 target graph，六库通过 privacy/export audit。

待补：

- [ ] 显式 seed 模式的 deterministic native/report 双跑，以及默认模式的 diversity 双跑。
- [ ] 在非 host OS/JVM 上运行同一状态隔离场景。

## LLVM `opaquePredicates`

当前 `LlvmOpaquePredicatePass` 只操作 validated `LlvmModule`：

- 对 conditional branch，在原 condition 前加入 defined `xor i32 C, C`、`icmp eq ... 0`，再与原 condition 做 `and i1`。
- 不使用 poison、undef、signed-overflow flag、invalid shift、misaligned memory 或 JNI/runtime side effect。
- function 无 conditional branch 时稳定 `SKIPPED`；input/output module 都经过 `LlvmModuleValidator`。
- 当前 predicate 是 model-level 恒真 gate。`ReleaseSafe` optimizer 可以把它折叠掉，因此不能宣称最终 binary 一定保留 opaque branch。

已完成：

- [x] disabled identity、seed-deterministic defined-integer predicate、no-candidate focused tests。
- [x] combined LLVM pipeline model -> text golden 与 validator coverage。
- [x] mainline `LLVM_OPAQUE_PREDICATES` report 接线。
- [x] Windows real-Zig host branch E2E 已断言 `LLVM_OPAQUE_PREDICATES=RAN`、output parity 和 emitted LLVM 中的 `j2ll_opq_*` marker。
- [x] 六目标专项测试已断言 pass `RAN`、`j2ll_opq_*` 位于进入每个 target graph 的共享 LLVM，并完成六库 export/privacy audit。

待补：

- [ ] 补 loop 等更多 host shape，并继续区分 model evidence 与 optimized binary evidence。

## LLVM `blockLayoutPerturbation`

当前 pass 是纯 emission-order 变换：

- entry block 固定在 index 0。
- function 至少 3 个 block 时，non-entry blocks 按 seed-derived key 稳定排序；排序未改变时 deterministic rotate。
- terminator target、phi incoming、instruction、exception/helper semantics 均不修改。
- input/output module 都经过 validator。
- 该 pass 只保证 generated `.ll` 的 block order 改变；LLVM optimizer/linker 可以重新布局，不承诺最终 machine code 地址顺序。

已完成：

- [x] disabled identity、entry 固定、branch/phi reference preservation 和 seed determinism focused tests。
- [x] combined LLVM pipeline model -> text golden 与 validator coverage。
- [x] mainline `LLVM_BLOCK_LAYOUT_PERTURBATION` report 接线。
- [x] Windows real-Zig host branch E2E 已断言 `LLVM_BLOCK_LAYOUT_PERTURBATION=RAN` 与 output parity。
- [x] 六目标专项测试已断言 pass `RAN`、reordered shared LLVM 进入每个 target graph，并完成六库 export/privacy audit。

待补：

- [ ] 补 loop/switch 等更多 host shape。

## LLVM `globalLayout`

当前实现是严格受限的 LLVM global emission-slot perturbation：

- 只选择 `private`/`internal` module-local globals，且至少需要两个候选。
- 只在原 candidate slots 间按 seed-derived order 重排完整 `LlvmGlobal` 对象。
- global name、definition、initializer、alignment、section、mutability 和所有 references 保持不变。
- non-local globals 与 `llvm.used` 等 retention roots 保持原 slot。
- 当前不重排或编码 generated C 中的 JNI registration table、string carrier 或 native field storage；这些不是 `LlvmModule` globals。

已完成：

- [x] local-only reorder、reference/definition preservation、seed determinism、collision tie-break、disabled/single-candidate no-op 和 invalid-input tests。
- [x] combined LLVM pipeline model -> text golden 与 validator coverage。
- [x] mainline `LLVM_GLOBAL_LAYOUT` report 接线，affected symbols 使用已去语义化的 native global names。
- [x] Windows real-Zig host E2E 已断言 `LLVM_GLOBAL_LAYOUT=RAN`、affected globals 保留在 emitted LLVM、output parity，且最终动态库只导出 `JNI_OnLoad`。
- [x] 六目标专项测试已验证 affected globals 保留在共享 emitted LLVM、该 source 进入每个 target graph，并完成六库 export/privacy audit。

## 后续证据收口顺序

1. 扩展 host boundary shapes：loop/switch、method-table multi-owner 与 virtual/interface registration；devirtualized/null/class-init call 需先补 backend。
2. 为 field/native report 补 deterministic real-Zig dual-run evidence。
3. 在 Linux/macOS JVM（以及与当前 host 不同的目标环境）补 runtime child-JVM evidence；cross-link 成功继续只记 structural support。
