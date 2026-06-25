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
- config 启用了尚未实现的 protection pass 时 warning + ignore；pass 对某个 method 不适用时 warning + skip that pass。
- protection pass 必须支持固定 seed，以便复现和测试。

## 测试

- 每个 pass 使用手写 IR model 测试。
- pass pipeline 测试验证顺序和每步 validator。
- 保护 pass 保持旧实现的行为意图，并补充不会破坏 SSA/control-flow 的测试。
- 每个 protection pass 至少有 deterministic seed test、disabled no-op test、validator test。
