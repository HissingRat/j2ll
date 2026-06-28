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
- conservative fallback metadata

## 推荐包

```text
xyz.melodysky.analysis.callgraph
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
- interface methods with Code participate in call graph normally. Abstract/no-Code interface declarations are selector/report concerns and are recorded as `notApplicable` when matched; they do not become call graph targets with synthetic bodies.

## RTA

RTA 是第二步增强。

规则：

- 从 selected/native-lowered entry methods 出发做 reachability。
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
- fallback requirement
- reason when not devirtualized
- whether fallback requires JVM helper fallback and therefore makes the caller `halfLowered`

如果未来引入 guarded devirtualization，plan 还需要描述 guard condition 和 fallback target。第一版可以只做 unguarded safe devirtualization。

当前 JVM-hosted runtime dispatch helper subset 不实现 native vtable 或 object layout。对无法安全 devirtualize 但 descriptor 在 helper matrix 内的 virtual/interface call，plan/lowering 可以选择 `DISPATCH_HELPER` / `DEFERRED_DISPATCH_HELPER`：no-arg int、int-arg int、reference return、single-reference-argument/reference-return 通过 tokenized JNI helper 执行 `GetObjectClass` / `GetMethodID` / `Call<Type>Method`，保留 JVM override/interface dispatch 和 pending-exception 语义。当前 child JVM E2E 已覆盖 class inherited default-interface method、class override default method，以及 unrelated default providers 的 conflict boundary；report 额外标记 `DEFAULT_INTERFACE_DISPATCH_HELPER`，conflict/diamond boundary 追加 `UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT` / `DEFAULT_INTERFACE_DISPATCH_FALLBACK`。更复杂 descriptor、incomplete hierarchy sensitive shape 仍必须报告明确 fallback reason。

## 测试

- CHA final receiver 单目标。
- CHA 多 subtype 多目标。
- interface 多实现。
- missing external type 保守回退。
- RTA 排除未实例化 subtype。
- RTA 遇到 unknown allocation 后回退。
- devirtualization plan 输出 reason。
