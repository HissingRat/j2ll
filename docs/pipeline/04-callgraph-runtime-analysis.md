# 04 Call Graph And Runtime Analysis

本阶段建立调用事实和 runtime type facts。目标是先保守正确，再逐步提高精度。

## 输入

- `ParsedClass`
- `BytecodeCfg`
- `ClassHierarchy`
- native-lowered method selection
- world model

## 输出

- `CallGraph`
- `ReachabilityResult`
- `RuntimeTypeResult`
- `DevirtualizationPlan`
- `NativeMethodUseIndex`
- `NativeMethodInternalizationPlan`
- conservative dispatch and unsupported-boundary metadata

## 推荐包

```text
xyz.melodysky.analysis.callgraph
xyz.melodysky.analysis.method
xyz.melodysky.analysis.runtime
```

推荐类型：

- `CallSite`
- `CallTarget`
- `CallGraph`
- `CallGraphBuilder`
- `ChaCallResolver`
- `RtaCallResolver`
- `RuntimeAnalysisPipeline`
- `AllocationSiteModel`
- `DevirtualizationPlanner`
- `DevirtualizationPlan`

## CHA

CHA 是第一版主线能力。

规则：

- `invokestatic`：目标唯一。
- `invokespecial`：目标由 special lookup 规则决定，保持保守。
- `invokevirtual`：从 declared receiver type 的所有 subtype 中查找 override。
- `invokeinterface`：从 interface implementors 中查找实现。
- final class / final method 可以产生单目标。
- hierarchy incomplete 时必须保留 unknown external target。
- interface default method、bridge method、synthetic method、covariant return 需要有明确 lookup policy。
- interface methods with Code participate in call graph normally. Abstract/no-Code interface declarations are selector/eligibility-report concerns; they do not receive an executable-method status and do not become call graph targets with synthetic bodies.

## RTA

主线先通过`ProgramEntryPointPlanner`冻结保守entry集合，再由
`ProgramCallGraphAnalysisCoordinator`建立CHA并做method/allocation固定点。entry集合包含
本次selector命中的全部Code-bearing method、全部non-private Code方法、所有`<clinit>`、
closed catalog识别的JVM/JDK callback和已精确解析的reflection invoke/new-instance target；
存在unsupported reflection site时回退为全部Code方法。只有声明为`CLOSED_WORLD`时
才让`RtaCallResolver`收窄virtual/interface targets；每一轮只扫描当前reachable method的
allocation，已知direct/RTA target再扩展下一轮reachability，直到method与runtime-type集合
同时稳定。未被entry-rooted call closure触达的方法不能仅凭其中的`new`影响RTA。
instance entry及reference参数会把closed hierarchy中的具体receiver候选作为初始runtime
type，避免把JVM调用方传入的合法subtype误判为“不可能”。partial或unknown world始终保留
CHA结果，不能因当前allocation集合较小而假定外部subtype不存在。

规则：

- 从冻结的selected/external/JVM-lifecycle/reflection entry methods出发做reachability。
- 扫描 allocation site，记录 instantiated classes。
- virtual/interface target 集合 = CHA targets 与 instantiated classes 的交集。
- 遇到反射、JNI callback、class loading、unknown allocation 时标注 conservative mode。
- conservative mode 不等于失败；它应该让后续 lowering 选择 runtime dispatch。

## Points-to（可选）

Points-to 不进入第一轮必做。引入时应先定义：

- allocation site identity
- local/field/array points-to set
- method summary
- unknown heap policy

## Escape Analysis（可选）

Escape analysis 只在 points-to facts 足够稳定后加入。输出应当是 optimization hint，不改变 call graph 的保守正确性。

## Devirtualization

`DevirtualizationPlan` 不直接改 bytecode，也不直接发 LLVM。它只描述：

- call site id
- original call kind
- resolved target set
- selected direct target when safe
- runtime dispatch requirement
- reason when not devirtualized
- whether the call remains safely expressible through a JVM/JNI runtime helper
- whether the unsupported call shape makes the complete caller method `skipped`

如果未来引入 guarded devirtualization，plan 还需要描述 guard condition 和 slow-path target。第一版可以只做 unguarded safe devirtualization。

当前`BytecodeToSsaLowerer`按exact bytecode call-site id消费这一immutable plan。已证明为
unguarded single target的virtual/interface site会生成保留receiver操作数、但symbol绑定exact
resolved method key的`CALL_DIRECT`；其余site保持`CALL_VIRTUAL`/`CALL_INTERFACE`并走既有
JVM dispatch边界。plan缺失、invoke kind漂移或重复site在lowering阶段fail closed。
`ProgramIrProtectionCoordinator`只消费已经冻结进IR的`CALL_DIRECT`，不再回读call graph或按
resolved-target数量重新判定。method internalization仍消费RTA后的effective call graph，LLVM
backend只消费最终plan/IR。

正常build的`lowering-report.json.callAnalysis`必须逐项记录exact bytecode call-site id、
caller、instruction index、declared/resolved/direct target、reachability与决策reason；只写
汇总计数或仅在可选intermediates中写证据不算主线收口。`DevirtualizationPlan`与effective
`CallGraph`必须对每个call-site id一一覆盖，duplicate/missing/kind/target drift均fail closed。

当前 JVM-hosted runtime dispatch helper subset 不实现 native vtable 或 object layout。对无法安全 devirtualize 但 descriptor 在 helper matrix 内的 virtual/interface call，plan/lowering 可以选择 `DISPATCH_HELPER` / `DEFERRED_DISPATCH_HELPER`：no-arg int、int-arg int、reference return、single-reference-argument/reference-return 通过 tokenized JNI helper 执行 `GetObjectClass` / `GetMethodID` / `Call<Type>Method`，保留 JVM override/interface dispatch 和 pending-exception 语义。只要完整方法最终拥有可执行 native implementation，这种 JVM/JNI helper-backed 路径仍记录为 `nativeLowered`。当前 child JVM E2E 已覆盖 class inherited default-interface method 和 class override default method。conflict/diamond 或更复杂 descriptor、incomplete-hierarchy-sensitive shape 无法由当前 helper 保持语义时，完整 caller 记录为 `skipped`，保留原 Code，不生成 native registration，并报告明确 reason。

## Method Internalization Use Analysis

`NativeMethodUseAnalyzer` 在 final native implementation plan 已知后建立 method-use index；它不能只复用 call graph 的“可能 target”集合，因为删除 Java declaration 还必须覆盖 classfile metadata observer：

- 扫描 input 与 declared-closed-world classpath 中的 method instructions。
- 递归扫描 LDC `Handle`、invokedynamic/ConstantDynamic bootstrap arguments。
- 记录 exact reflection/MethodHandles lookup 与 `EnclosingMethod` references。
- static call 保留 exact symbolic target；instance call同时保存 invoke kind、scope内exact-target证据和unknown external target。

Planner只批准已有final `LLVM_NATIVE_PATH`的private/protected static，或same-owner exact private/protected instance method。required exact allowlist另可授权public static及same-owner exact public instance：public static可使用declared `CLOSED_WORLD`或本次current-JAR-only Y授权；public instance只接受declared `CLOSED_WORLD`与parse-complete input+configured-classpath world。缺失任一superclass/interface会产生`MISSING_EXTERNAL_CLASS`并只以`METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE`保留相关public instance候选，不把可继续证明的public static/private/protected候选整体关闭。public instance不要求method/class为final，也不因override slot本身拒绝，但每个调用点必须exact且caller仍须same-owner；实际non-exact dispatch会保留Java入口。每个incoming caller都必须已有final LLVM implementation和native direct/dispatch route。cross-owner protected/public static允许通过defining-class native bridge进入hidden target；cross-owner instance、interface/non-exact virtual、unselected/skipped caller、已解析的exact reflection/MethodHandle/Handle/bootstrap/ConstantDynamic/EnclosingMethod observer与已知external Java entry都保留Java入口。无法穷举的reflection/JNI/agent动态观察面进入warning/report，由exact allowlist与world授权接受风险；current-JAR-only明确把configured classpath及JAR外caller/subclass/observer排除在证据外，不伪装成`CLOSED_WORLD`或“证明不存在”。

## 测试

- CHA final receiver 单目标。
- CHA 多 subtype 多目标。
- interface 多实现。
- missing external type 的保守 dispatch/unsupported 决策。
- RTA 排除未实例化 subtype。
- RTA 遇到 unknown allocation 后恢复保守 target set。
- devirtualization plan 输出 reason。
- protected/public static在declared/current-JAR scope下的cross-owner决策，以及same-owner protected/public instance method-use/internalization决策。
- 非final public instance在所有调用点exact时可批准；实际override导致non-exact dispatch时拒绝。
- 已解析exact Handle/bootstrap/ConstantDynamic/reflection/EnclosingMethod与known external entry拒绝；unsupported/unbounded动态observer只产生风险warning/report。
