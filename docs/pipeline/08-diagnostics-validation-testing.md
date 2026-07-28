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
- method decision, only `nativeLowered` or `skipped` for selected methods with Code
- selector scope evidence such as `excluded`
- no-Code eligibility evidence, kept separately from method decision

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

如果某个 operation/call site 能通过已实现的 JVM/JNI runtime helper 保持完整语义，并且最终 native implementation 与 ABI 校验均通过，整个 method 记录为 `nativeLowered`。helper-backed 不等于 Java bytecode compatibility path。

只要 selected 且有 Code 的 method 无法形成完整 native implementation，就把整个 method 记录为 `skipped`，保留原 Code，不生成 native stub、helper body 或 `RegisterNatives` binding。不得保留局部 lowering 后再通过原字节码补齐语义。

如果 selector 命中 abstract method、already-native method、没有 Code 的 interface method或 annotation element，记录为独立 eligibility evidence。它不是 method lowering status、warning 或 build failure，也不触发 skipped-method confirmation；但必须出现在 report 中，方便确认 selector 没有被静默忽略。build/validation/toolchain failure 是 invocation-level failure，不伪装成 method status。

no-Code 是这条 eligibility 例外的边界。selected 且有 Code 的 base method 即使因为 multi-release counterpart 等 packaging safety policy 不能改写，也必须记录为 `skipped`（例如 `MULTI_RELEASE_VERSIONED_CLASS`）并参与确认。

## Skipped Method Confirmation

默认 build 在 final implementation plan 已稳定后、创建任何 Zig workspace 或启动任何 Zig invocation 之前，必须对所有 `skipped` method 做一次确定性确认：

1. stderr 逐项输出 `owner#name!descriptor`、`reasonCode` 和 user-facing reason。
2. 明确警告这些方法不会 native lowered，输出 JAR 将保留它们原来的 Code。
3. 输出 `continue? (Y/N)`；只有大小写不敏感的显式 `Y` 继续，`N` 或 EOF 都终止本次 build。
4. 拒绝时不得创建 Zig workspace、不得调用 Zig、不得写 final JAR，并写出 lowering-stage failure/summary evidence。

无 `skipped` method 时不输出列表也不询问。`--validate` 和 `--dry-run` 不读取 stdin；它们只记录 `confirmationRequired`/deferred warning，说明最终列表要等默认 build 的 final plan 才能确定。显式管道输入 `Y` 必须可用；如果同一 invocation 还有其他确认，CLI 必须共享同一个 reader，不能因 reader buffering 丢失后续输入。

该确认是全局方法覆盖契约，不引入 `requiredNative` selector/config。

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
- Packaging validator：manifest/resource preservation、loader presence、native registration completeness、skipped-method no-rewrite/no-registration、embedded library layout。

SSA dominance 以 method 第一个 block 为 entry，并同时把普通 terminator edge、显式 throw edge和 instruction-level exception handler edge纳入 CFG。method parameter支配所有可达 block；block parameter在本 block 入口定义；instruction result必须在同 block use之前定义，或由严格支配 use block的定义产生。检查范围包括instruction operands、return/throw value、branch/switch condition、所有normal target arguments、exception-edge arguments，以及exception-site handler arguments。exception-site `exceptionValue`只在该site对应的handler arguments中可用，不能泄漏到正常continuation。不可达 block稳定报告 `IR_UNREACHABLE_BLOCK`，且不再追加误导性的dominance/use-before-def噪声。

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
- 加 protected JVM exception flow：必须同时覆盖throw-site locals/handler arguments、IR validator、pending/clear/typed-dispatch/rethrow LLVM shape，以及real-Zig host child-JVM differential；non-host runtime证据单独记录。
- 改constructor/`<clinit>` boundary：必须覆盖initializer plan、verifier、rewriter/registration一致性和child-JVM class-init ordering。
- 加 analysis：必须有 focused analysis test；涉及 invoke/devirtualization 时补 pipeline test。
- 加 pass：必须有 pass unit test。
- 改 pipeline 编排：必须有 pipeline test。

额外建议：

- Differential tests：同一 fixture 同时跑 JVM 原始结果和 native-lowered 结果。
- Corpus tests：收集真实 `.class`，至少跑 parse/CFG/hierarchy/diagnostic。
- Regression tests：每个曾经的 unsupported/bug 都保留最小 class fixture。
- Determinism tests：关键 diagnostics 和 dump 输出排序稳定。
