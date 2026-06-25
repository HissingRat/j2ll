# Pipeline Guides

这里是 rewrite 后新编译管线的实现 guide 索引。总路线图见 [`../rewrite-roadmap.md`](../rewrite-roadmap.md)，项目结构和类职责见 [`../project-structure.md`](../project-structure.md)，Java/JVM 特性分层见 [`../java-support-tiers.md`](../java-support-tiers.md)，保护/混淆设计见 [`../protection-obfuscation.md`](../protection-obfuscation.md)，输入/配置/输出契约见 [`../io-config-output-contract.md`](../io-config-output-contract.md)。

新主线只写入：

```text
src/main/java
src/test/java
```

旧目录只作为 legacy reference：

```text
obfuscator/src/main/java
obfuscator/src/test/java
obfuscator/bench
```

## 目标管线

```text
ClassFileSource
  -> AsmClassParser
  -> MethodCfgBuilder
  -> ClassHierarchyBuilder
  -> RuntimeAnalysisPipeline
  -> BytecodeToSsaLowerer
  -> OptimizationPipeline
  -> ProtectionPipeline
  -> LlvmModuleLowerer
  -> LlvmProtectionPipeline
  -> LlvmTextEmitter
  -> NativeLinkAndSymbolAudit
  -> Repackager
```

## 分阶段 guide

1. [`00-overview.md`](00-overview.md)：主线编排、source tree、clean-room bootstrap。
2. [`01-classfile-parse.md`](01-classfile-parse.md)：`.class` / JAR 输入与 ASM parse。
3. [`02-method-cfg.md`](02-method-cfg.md)：method-level bytecode CFG。
4. [`03-class-hierarchy.md`](03-class-hierarchy.md)：class hierarchy、method lookup、world model。
5. [`04-callgraph-runtime-analysis.md`](04-callgraph-runtime-analysis.md)：CHA、RTA、points-to、escape、devirtualization。
6. [`05-bytecode-to-ssa.md`](05-bytecode-to-ssa.md)：栈式 bytecode 到三地址 SSA IR。
7. [`06-optimization-passes.md`](06-optimization-passes.md)：method/program optimization pass。
8. [`07-llvm-backend.md`](07-llvm-backend.md)：LLVM IR emission 和 backend 边界。
9. [`08-diagnostics-validation-testing.md`](08-diagnostics-validation-testing.md)：diagnostics、validator、测试矩阵。
10. [`09-debug-dumps-docs.md`](09-debug-dumps-docs.md)：debug dump、可观测性、文档维护。
11. [`10-packaging-native-registration.md`](10-packaging-native-registration.md)：JAR rewrite、loader、native registration、fallback blob。

## 维护规则

- 新增 stage、fallback 策略、validator、测试落点或目录边界时，先更新 `AGENTS.md`，再更新对应 stage guide。
- README 保持用户视角；内部 rewrite 计划和 compiler 设计只放在 `docs/`。
- 单个 stage guide 应保持 focused。跨阶段规则放在本索引、`00-overview.md` 或 `08-diagnostics-validation-testing.md`。
