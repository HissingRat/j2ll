# 06 Optimization Passes

本阶段把 validated SSA IR 交给优化和保护 pass。pass pipeline 必须保持可验证、可调试、顺序明确。保护/混淆的完整设计见 [`../protection-obfuscation.md`](../protection-obfuscation.md)。

## 输入

- validated `IrProgram`
- optional analysis facts
- pass configuration

## 输出

- optimized `IrProgram`
- pass diagnostics

## 推荐包

```text
xyz.melodysky.ir.pass
xyz.melodysky.ir.pass.protection
```

推荐类型：

- `IrMethodPass`
- `IrProgramPass`
- `OptimizationPipeline`
- `PassContext`
- `PassDiagnostics`
- `PassValidatorHook`
- `ProtectionPipeline`
- `ProtectionPass`
- `ProtectionConfig`

## Pass 分类

基础优化：

- CFG cleanup
- dead block elimination
- constant folding
- copy propagation
- trivial phi cleanup
- dead instruction elimination

保护/混淆：

- control-flow flattening
- fake branches / opaque predicates
- basic block splitting
- constant encryption
- string encryption
- method inlining/splitting
- call indirection
- method table hiding plan

当前 v1 已实现并默认启用的 IR protection 子集：

- `ControlFlowFlatteningPass`：先规划bounded single-entry/multi-exit safe regions，再为每个
  获准region生成独立dispatcher state switch。每个method最多4个互不重叠region，每个
  region最多32个原始member block；owned/exception/handler/monitor/JMM/class-init敏感block
  留在region外并保持原样。
- `StringEncryptionPass`：加密 `j2ll_rt_string_constant|string:<literal>` carrier、普通 `CONST_STRING` / `ldc String`，以及安全 TEMPLATE constructor body string literal，输出 encrypted native `j2ll_rt_string_constant|enc:v1:<token>:<keyHex>:<cipherHex>` helper call；reflection / lambda / MethodHandle bootstrap metadata 相关 method 保守记录 skip。
- `FakeBranchesPass`：对安全 method 插入 deterministic predicate gate/detour；无动态参数时的 constant predicate 可能被 `ReleaseSafe` 折叠。
- `BasicBlockSplittingPass`：在安全 instruction boundary 拆分 eligible block，只做 block split，不插入 fake branch。
- `PrimitiveConstantEncryptionPass`：对安全 method 的 `CONST_INT` / `CONST_LONG` 生成 deterministic XOR decode sequence；对 `CONST_FLOAT` / `CONST_DOUBLE` 加密 raw bit pattern，再通过 LLVM bitcast 恢复 JVM 浮点值。
- `BlockNameObfuscationPass`：按 seed 稳定重命名 block。
- `MethodInliningPass`：program-level pure-scalar static/private-self direct callee 子集。前端 direct call 即使没有 handler 也会携带 pending-exception evidence；只有 callee 已证明不产生 exception、site 没有 handler，且 synthetic exception value 完全无使用时，inline 才能删除该 evidence。protected edge、具体 exception check 或仍被使用的 exception value 继续 fail closed。
- `MethodSplittingPass`：program-level single-block scalar suffix outline，helper 只进入 compiler-internal LLVM/native path。
- `IrCallIndirectionPass`：当前只为已证明的 module-local same-owner static/private-special call 附加 typed semantic plan，再由 backend lower 到 hidden pointer table；group key 同时包含 Java/SSA signature 与最终 native function 的隐藏 `JNIEnv*` / owner-`jclass` ABI proof，禁止把表面 IR signature 相同但真实 LLVM function-pointer type 不同的目标混入一组。virtual/interface 和 cross-owner direct call 在 backend 支持前 fail closed。
- `NativeFieldIrRewriter`：在 declared `CLOSED_WORLD`，或 build 时由用户明确批准的 current-JAR-only field plan 后，把 same-owner static 或 instance LLVM-native accessor 对可变 `private static boolean/byte/short/char/int/long/float/double` 与 reference/array field 的访问改成带 exact storage-kind 的 opaque slot；primitive 进入 native raw-bit storage，reference/array 进入 JVM-managed ClassValue sidecar。配套的`NativeConstantFieldIrFolder`把获准primitive `ConstantValue`显式读取替换为SSA常量且不分配slot；显式String读取暂不折叠。instance wrapper使用field的declared defining `jclass`，不按receiver runtime class拆分static storage。current-JAR-only 不改变 configured world、不读取 classpath，并在报告中保留风险边界。该 Config field 默认关闭。
- 常量字段与可变字段共享独立dynamic-observer fail-closed分析：exact target只阻断对应字段，known-owner unresolved lookup/scan阻断该owner，来源未知的`Field`/MethodHandle/VarHandle/Unsafe访问阻断所有候选。分析扫描input与已纳入world的classpath bytecode，不依赖method-reflection giant resolver。`ConstantBootstraps.getStaticFinal`/field VarHandle使用exact shared resolver；所有非exact closed-JDK allowlist的custom indy/condy bootstrap target、不可解的bootstrap MethodHandle argument、native/agent边界以及定义新bytecode的`defineClass`/hidden-class/Unsafe define入口global fail closed。

`methodInternalization`不是SSA rewrite pass；它消费final native implementation plan与`analysis.method`的immutable use plan。exact allowlisted public static可使用declared `CLOSED_WORLD`或本次current-JAR-only Y授权，public instance只接受declared `CLOSED_WORLD`、same-owner caller closure和逐调用点exact dispatch；不要求method/class为final，也不因可覆写slot本身拒绝。已解析exact observer会保留Java入口，unsupported/unbounded reflection/JNI/agent surface只作为user-accepted risk进入warning/report。批准项的registration过滤、native route与MethodNode删除分别由final planner/toolchain/packaging负责，IR pass不得自行推断或删除Java入口。

普通optimization之后、protection之前运行`JdkPureNativeIntrinsicPass`。当前只匹配same-block、unique-use且不逃逸的精确`ByteBuffer.allocate(4).putInt(i).array()`，把对象式JDK dispatch融合为保持三个异常site顺序的native frame helper。它不把Java object放进native heap；结果仍由JNI `NewByteArray`创建。

`ActiveUseCarrierFusionPass`随后只融合一种精确same-block、无间隔链：
`CONST_LONG -> CLASS_OBJECT -> CLASS_INIT_GUARD -> CLASS_INIT_HAPPENS_BEFORE ->
GET_STATIC/PUT_STATIC/CALL_STATIC`。token必须只被class-object使用一次，class-object
必须只被guard和happens-before使用；class symbol、field/method owner必须是
同一个exact JVM internal name，guard symbol必须为该class的
`:superBeforeSubclass`，happens-before symbol必须为`classInitGuard`。
`CLASS_OBJECT`、`CLASS_INIT_GUARD`与terminal active operation必须具有相同的
规范化exception/handler boundary，且任何instruction都不得带call-indirection。
`CALL_STATIC`只有在target不可能转成direct native call时才融合；否则direct
LLVM call无法代替JVM active-use语义。`NEW_OBJECT`明确不在适用集内，
因为当前`AllocObject`路径不保证触发class initialization。owner不同、
carrier额外使用、exception boundary不同、instruction gap或任何非精确shape
都保留原carrier，不做部分融合。

获准后四个carrier被移除，原JVM-backed active operation保留，并在它之后
插入`CLASS_INIT_ACTIVE_USE`。backend将该marker lower为post-operation
`fence acquire`；这不是预先猜测初始化或在native中伪造class-init。当active
operation产生JNI pending exception时，物理block split先进入exception transfer，
acquire只在normal continuation执行，从而保留失败的`<clinit>`的
`ExceptionInInitializerError`/`NoClassDefFoundError`语义。

`NativeOnlyMethodCoalescingCoordinator`位于method internalization之后。它只消费immutable final-use/implementation facts，并复用严格inlining safety proof把单一直接call site的pure-scalar、non-throwing小callee合并到caller。callee一旦涉及field、nested call、monitor、JNI/reference ownership、recursion或超过bounded size即保持独立hidden LLVM body。该优化改变physical retention report，不新增method outcome。

该stage逐callee持久化coverage：已经完成证明但shape不适用时写`notApplicable/SKIPPED`；inliner或IR validator失败而无法可靠判定时写`unknown/FAILED`并保留原standalone body，不能把内部验证失败伪装成普通不适用。

`methodTableHiding` 不是 SSA method pass；它消费 final native registration plan，在 packaging/toolchain 层生成 build-diverse owner-local registration order，并只在注册窗口构造临时 `JNINativeMethod[]`。report token 不进入 generated C/native，也不生成 split runtime tables。当前 schema v1 的已知 IR/LLVM pass 字段均已实现；单 method/module 不适用仍只跳过对应 pass并写 protection report，不改变 lowering status。

当前 v1 已实现的 LLVM protection 子集：

- `CALL_INDIRECTION`：对 same-class selected static/private direct LLVM call，在 `LlvmModule` model 中默认生成 deterministic hidden signature-group function-pointer table `j2ll_cit_<sha256>`，caller load function pointer 后 indirect call；成功记录 `CALL_INDIRECTION_TABLE`，无适用 table shape 记录 `CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE` skip。dispatcher switch `j2ll_cid_<sha256>` 仍作为 fallback 形态，成功记录 `CALL_INDIRECTION_DISPATCHER`。
- `LLVM_NAME_OBFUSCATION`：由共享 `LlvmNameMangler` 在 planner/lowerer/Zig workspace/JNI wrapper 之间提供 deterministic hidden function symbol。
- `LLVM_OPAQUE_PREDICATES`：对 conditional branch 添加 defined-integer 恒真 gate；model 变换真实存在，但 optimizer 可以折叠。
- `LLVM_BLOCK_LAYOUT_PERTURBATION`：固定 entry，只改变 non-entry block textual/emission order。
- `LLVM_GLOBAL_LAYOUT`：只在现有 candidate slots 间重排完整 module-local globals，不改 definition/reference/alignment/section，也不处理 generated-C tables。

所有 LLVM pass 在 text emission 前操作 `LlvmModule` 并做 input/output validation。各项host/六目标/optimizer-sensitive machine-code retention/non-host runtime状态见 [`../protection-implementation-checklist.md`](../protection-implementation-checklist.md)；final-plan method internalization不伪装成LLVM module pass。

## 顺序建议

第一版可读顺序：

```text
validate
  -> canonical CFG cleanup
  -> simple scalar cleanup
  -> exact JDK call-combination intrinsics
  -> exact active-use carrier fusion
  -> protection passes
  -> protection-aware validation
  -> final CFG cleanup
  -> validate
```

长期建议：

```text
canonicalize
  -> verify
  -> analysis-preserving scalar opts
  -> analysis-consuming opts
  -> protection passes
  -> canonicalize
  -> verify
```

注意：保护 pass 后的 cleanup 必须保护感知，不能把刚插入的 fake branch、flatten dispatcher 或 indirect call 全部清掉。

## 边界

- pass 不读取 ASM。
- pass 不修改 class hierarchy。
- method pass 不跨方法推断事实。
- program pass 如果需要 analysis facts，应显式通过 `PassContext` 注入。
- pass 不能依赖未声明的 IR 形态；如果需要 canonical input，放在 pass contract 中。
- protection pass 必须声明是否保持 SSA、是否改变 CFG、是否需要 runtime helper、是否可能按 method 跳过 pass。
- 当前 schema 字段都已接线；未来 known-but-unavailable pass 仍必须 warning + ignore。pass 对某个 method/module 不适用时 warning + skip that pass。
- protection pass 必须支持固定 seed，以便复现和测试。
- `<init>` / `<clinit>` body-helper shape继续保守跳过CFF；其他method中的
  monitor/JMM/exception/class-init敏感block由region planner排除，不能让保护pass破坏
  JVM-visible helper semantics。
- control-flow flattening只把当前dispatcher ABI可完整表达的block放入region。含block
  parameter或edge target argument的block、产生跨基本块instruction-defined SSA live value
  的definition block以及owned-reference producer均留在region外；若因此没有至少2个block
  的single-entry region，pass以稳定`CONTROL_FLOW_FLATTENING_*`原因跳过并保留原始合法IR。
- region之间不得重叠。region内部edge经region-local dense state transition，状态范围为
  `[0, regionMemberCount)`；multi-exit edge直接保留原region外target和语义，不经过其他
  region或method-global dispatcher。
- protection pipeline 输入先做 IR validation；输入本身非法仍是 build-level error。每次 protection pass 后再次运行 validator；candidate 失败时该 pass 记录 `FAILED`、回滚到该 pass 的合法输入，并以 `PASS_VALIDATION_FAILED` warning 携带稳定 validator code evidence，不能让后续 pass 或 backend 接收、修补非法 candidate。

## 测试

- 每个 pass 使用手写 IR model 测试。
- pass pipeline 测试验证顺序和每步 validator。
- 保护 pass 保持旧实现的行为意图，并补充不会破坏 SSA/control-flow 的测试。
- 每个 protection pass 至少有 deterministic seed test、disabled no-op test、validator test。
- CFF另需覆盖4-region/32-member预算、single-entry pruning、multi-exit、region外owned与
  typed-exception block identity preservation，以及“至少一个region成功才affected”的报告语义。
