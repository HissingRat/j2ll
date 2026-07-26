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

- `ControlFlowFlatteningPass`：对安全 multi-block primitive LLVM-native method 生成 dispatcher state switch；monitor/exception/block-parameter/helper-sensitive shape 保守跳过。
- `StringEncryptionPass`：加密 `j2ll_rt_string_constant|string:<literal>` carrier、普通 `CONST_STRING` / `ldc String`，以及安全 TEMPLATE constructor body string literal，输出 encrypted native `j2ll_rt_string_constant|enc:v1:<token>:<keyHex>:<cipherHex>` helper call；reflection / lambda / MethodHandle bootstrap metadata 相关 method 保守记录 skip。
- `FakeBranchesPass`：对安全 method 插入 deterministic predicate gate/detour；无动态参数时的 constant predicate 可能被 `ReleaseSafe` 折叠。
- `BasicBlockSplittingPass`：在安全 instruction boundary 拆分 eligible block，只做 block split，不插入 fake branch。
- `PrimitiveConstantEncryptionPass`：对安全 method 的 `CONST_INT` / `CONST_LONG` 生成 deterministic XOR decode sequence；对 `CONST_FLOAT` / `CONST_DOUBLE` 加密 raw bit pattern，再通过 LLVM bitcast 恢复 JVM 浮点值。
- `BlockNameObfuscationPass`：按 seed 稳定重命名 block。
- `MethodInliningPass`：program-level pure-scalar static/private-self direct callee 子集。
- `MethodSplittingPass`：program-level single-block scalar suffix outline，helper 只进入 compiler-internal LLVM/native path。
- `IrCallIndirectionPass`：当前只为已证明的 module-local same-owner static/private-special call 附加 typed semantic plan，再由 backend lower 到 hidden pointer table；virtual/interface 和 cross-owner direct call 在 backend 支持前 fail closed。
- `NativeFieldIrRewriter`：在 declared `CLOSED_WORLD`，或 build 时由用户明确批准的 current-JAR-only field plan 后，把 same-owner static `boolean/byte/short/char/int/long/float/double` 与 reference/array field access 改成带 exact storage-kind 的 opaque slot；primitive 进入 native raw-bit storage，reference/array 进入 JVM-managed ClassValue sidecar。current-JAR-only 不改变 configured world、不读取 classpath，并在报告中保留风险边界。该 Config field 默认关闭。

`methodTableHiding` 不是 SSA method pass；它消费 final native registration plan，在 packaging/toolchain 层生成 split token tables。当前 schema v1 的已知 IR/LLVM pass 字段均已实现；单 method/module 不适用仍只跳过对应 pass并写 protection report，不改变 lowering status。

当前 v1 已实现的 LLVM protection 子集：

- `CALL_INDIRECTION`：对 same-class selected static/private direct LLVM call，在 `LlvmModule` model 中默认生成 deterministic hidden signature-group function-pointer table `j2ll_cit_<sha256>`，caller load function pointer 后 indirect call；成功记录 `CALL_INDIRECTION_TABLE`，无适用 table shape 记录 `CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE` skip。dispatcher switch `j2ll_cid_<sha256>` 仍作为 fallback 形态，成功记录 `CALL_INDIRECTION_DISPATCHER`。
- `LLVM_NAME_OBFUSCATION`：由共享 `LlvmNameMangler` 在 planner/lowerer/Zig workspace/JNI wrapper 之间提供 deterministic hidden function symbol。
- `LLVM_OPAQUE_PREDICATES`：对 conditional branch 添加 defined-integer 恒真 gate；model 变换真实存在，但 optimizer 可以折叠。
- `LLVM_BLOCK_LAYOUT_PERTURBATION`：固定 entry，只改变 non-entry block textual/emission order。
- `LLVM_GLOBAL_LAYOUT`：只在现有 candidate slots 间重排完整 module-local globals，不改 definition/reference/alignment/section，也不处理 generated-C tables。

所有 LLVM pass 在 text emission 前操作 `LlvmModule` 并做 input/output validation。8 项的 Windows real-Zig host 与六目标 feature-specific structural evidence 已通过；optimizer-sensitive machine-code retention/non-host runtime 状态见 [`../protection-implementation-checklist.md`](../protection-implementation-checklist.md)。

## 顺序建议

第一版可读顺序：

```text
validate
  -> canonical CFG cleanup
  -> simple scalar cleanup
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
- `<init>` / `<clinit>` body-helper shape、monitor/JMM/exception/call/field/helper-sensitive method 默认保守跳过 CFG/constant protection，不让保护 pass 破坏 JVM-visible helper semantics。
- 每次 protection pass 后必须运行 IR validator；失败时该 pass 记录 `FAILED`，不能让 backend 修补非法 IR。

## 测试

- 每个 pass 使用手写 IR model 测试。
- pass pipeline 测试验证顺序和每步 validator。
- 保护 pass 保持旧实现的行为意图，并补充不会破坏 SSA/control-flow 的测试。
- 每个 protection pass 至少有 deterministic seed test、disabled no-op test、validator test。
