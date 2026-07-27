# 00 Pipeline Overview

本文描述 rewrite 后的新主线编排方式。旧实现只作为 legacy reference，新实现不在旧 `obfuscator/src` 里扩写。

## Source tree layout

新主线：

```text
src/main/java
src/test/java
```

legacy 参考：

```text
obfuscator/src/main/java
obfuscator/src/test/java
obfuscator/bench
```

规则：

- 新 production class 只放进 `src/main/java`。
- 新 unit/integration test 只放进 `src/test/java`。
- 旧 `obfuscator/src` 可以读取、对照、迁移测试意图，但不新增 clean-room 架构代码。
- 后续 Gradle source set 应切到新的 root `src` tree。
- 如需继续跑旧实现作对照，应创建明确的 legacy-only task，不把旧实现混进新主线 classpath。

## Stage pipeline

目标主流程：

```text
SourceDiscoveryResult
  -> ClassParseResult
  -> CfgBuildResult
  -> HierarchyResult
  -> CallGraphResult
  -> RuntimeAnalysisResult
  -> SsaLoweringResult
  -> OptimizationResult
  -> ProtectionResult
  -> LlvmModuleResult
  -> LlvmProtectionResult
  -> LlvmEmissionResult
  -> NativeLinkResult
  -> SymbolAuditResult
  -> PackagingResult
```

每个 result 至少包含：

- stage name
- successful artifacts
- diagnostics
- incomplete/conservative 标记

后续阶段只能消费前一阶段公开 artifact，不读取前一阶段 builder 的 mutable fields。

## Clean-room bootstrap

第一批实现不要急着支持完整 Java 语义，先建立这些硬骨架：

- stage result 类型。
- stage diagnostic 类型。
- stage validator 接口。
- pipeline runner。
- debug dump hook。
- 最小 happy-path 测试。
- 最小 unsupported/skipped 测试。

## Legacy reference rules

- 旧代码可以用来确认行为和迁移测试。
- 新代码不要直接复制旧的大类结构。
- 从旧代码摘取纯函数前，先确认它属于哪个 stage，并补 stage-local test。
- 不让 legacy package 进入生产 classpath。
- 新测试迁移到 `src/test/java` 的 stage-local package，不继续写进 `obfuscator/src/test/java`。

## Public API strategy

如果需要兼容旧调用方，可以在新树里重建薄 facade，例如：

```text
IrPipelineCompiler
JarIrBuilder
LlvmTextBackend
```

这些 facade 只负责适配旧 public API，不承载真实阶段逻辑。真实逻辑应位于各 stage package。
