# 08 Diagnostics, Validation, Testing

本文件定义跨阶段 diagnostics、validator 和测试矩阵。目标是让错误定位在正确 stage，不把正确性压力推给最终 backend。

## Diagnostic 策略

统一 diagnostic 字段：

- class name
- method name
- descriptor
- stage
- category
- severity: `info` / `warning` / `error`
- stable diagnostic code
- raw reason
- artifact id
- instruction offset when available
- decision, for example `lowered`, `halfLowered`, `frontendSkipped`, `notApplicable`, `failed`, `excluded`, `warning`
- conservative fallback available

stage 枚举建议：

```text
CONFIG
INPUT_DISCOVERY
PARSE
CFG
HIERARCHY
CALL_GRAPH
RUNTIME_ANALYSIS
LOWERING
VALIDATION
OPTIMIZATION
PROTECTION
LLVM_MODEL
LLVM_PROTECTION
LLVM_EMISSION
NATIVE_LINK
SYMBOL_AUDIT
PACKAGING
```

如果某个 operation/call site 可以走 JVM helper fallback，就把 method 标记为 `halfLowered` 并发 warning，不要直接 fail。只有无法保持语义正确或缺少必需输入时，才把 method 标记为 `frontendSkipped` 或 `failed`。

如果 selector 命中 abstract method、already-native method、没有 Code 的 interface method 或 annotation element，记录为 `notApplicable`。它不是 warning，也不是 build failure；但必须出现在 lowering report 中，方便确认白名单没有被静默忽略。

Report JSON 的最低字段和示例以 `docs/io-config-output-contract.md` 为准；本文件只维护 stage enum、validator 和测试落点。

Diagnostic 需要稳定排序，方便测试和回归定位。同一输入不应因为 HashMap iteration 或并发顺序产生不同报告。

## Validator 矩阵

每个 stage 都应有 validator 或 invariant tests：

- Parse validator：class/method identity、descriptor、instruction ownership。
- CFG validator：entry、terminator、successor、handler、unreachable 标记。
- Hierarchy validator：super/interface cycle、method signature uniqueness、external placeholder。
- Call graph validator：call site ownership、target existence、unknown target policy。
- SSA validator：use-before-def、dominance、type、phi/block parameter arity、terminator。
- Optimization validator：pass 前后 IR invariant。
- Protection validator：pass 前后 IR invariant、seed determinism、pass applicability diagnostics。
- LLVM model validator：type/linkage/control-flow/global initializer consistency。
- LLVM protection validator：module model invariant、export allowlist compatibility。
- Backend preflight：unsupported IR shape 必须明确报错。
- Native link validator：selected target artifact existence。
- Symbol audit validator：actual exports must match allowlist。
- Packaging validator：manifest/resource preservation、loader presence、native registration completeness、fallback blob policy、embedded library layout。

validator 失败应该指向 stage 和 artifact id，避免只给出泛泛的 `IllegalStateException`。

## 测试矩阵

新增功能时按影响面选择测试，不需要每次都跑完整矩阵。

推荐落点：

- class parsing：`frontend/classfile`
- CFG：`frontend/cfg`
- hierarchy：`analysis/hierarchy`
- call graph：`analysis/callgraph`
- runtime analysis：`analysis/runtime`
- bytecode lowering：`ir/ssa`
- IR invariants：`ir/validate`
- optimization：`ir/pass`
- protection：`ir/pass/protection`
- LLVM output：`backend/llvm`
- LLVM protection：`backend/llvm/protection`
- full pipeline：`pipeline`
- packaging/native build：`packaging`、`toolchain`

最低要求：

- 加 opcode lowering：必须有 frontend/SSA test。
- 加 IR instruction/terminator：必须有 validator test 和 backend test。
- 加 runtime helper：必须有 backend declaration test 和 runtime stub generator test。
- 加 analysis：必须有 focused analysis test；涉及 invoke/devirtualization 时补 pipeline test。
- 加 pass：必须有 pass unit test。
- 改 pipeline 编排：必须有 pipeline test。

额外建议：

- Differential tests：同一 fixture 同时跑 JVM 原始结果和 native-lowered 结果。
- Corpus tests：收集真实 `.class`，至少跑 parse/CFG/hierarchy/diagnostic。
- Regression tests：每个曾经的 unsupported/bug 都保留最小 class fixture。
- Determinism tests：关键 diagnostics 和 dump 输出排序稳定。
