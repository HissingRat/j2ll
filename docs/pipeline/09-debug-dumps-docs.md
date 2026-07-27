# 09 Debug Dumps And Docs

本文件定义 debug dump、可观测性和文档维护规则。

## Debug dump

新 pipeline 应能按 stage 输出 dump：

```text
intermediates/dumps/classfile/*.json
intermediates/dumps/cfg/*.txt
intermediates/dumps/hierarchy/*.json
intermediates/dumps/callgraph/*.json
intermediates/dumps/runtime-analysis/*.json
intermediates/dumps/ssa/*.ir
intermediates/dumps/optimized/*.ir
intermediates/dumps/protection/*.ir
intermediates/dumps/llvm-model/*.json
intermediates/dumps/llvm-protection/*.json
intermediates/dumps/llvm/*.ll
intermediates/dumps/native-link/*.json
intermediates/dumps/symbol-audit/*.json
intermediates/dumps/packaging/*.json
reports/release-suite-summary.json
```

dump 输出不是用户主界面，但对 rewrite 和回归定位非常重要。默认可关闭，debug/analyze 模式打开。

## Dump 原则

- 稳定排序。
- 不依赖对象 identity。
- 包含 stage name 和 artifact id。
- 不泄露不必要的大量 binary 数据。
- 适合被 test fixture 对比。

## Release Suite Observability

Release suite workspaces are test harness artifacts for release readiness, not a standalone runtime mode. They must preserve deterministic ordering and include:

- `reports/release-suite-summary.json` with suite name, profile (`smoke`/`standard`/`rc`), required/missing categories, case name/category/features, expected support statuses, original/output child JVM exit/stdout/stderr, report paths, diagnostics, signature policy and protection variant.
- the ordinary per-case reports: diagnostics, artifact audit, skipped-method, lowering, packaging, protection, symbol audit, support/opcode matrix, known blockers and release-readiness.
- useful failure diagnostics for expected failure cases such as invalid config, artifact audit failure, signed input rejected by `signaturePolicy: "fail"` or an injected/actual required-target capability, compile or link failure reported as `ZIG_TARGET_UNBUILDABLE`; selecting a non-host target alone is no longer a failure condition. Failed config/pipeline runs also write `failure-report.json` with `finalArtifactWritten=false`.
- known blocker coverage evidence: each blocker reason is expected to appear in a suite case expected status/diagnostic plus `expectedSupportEvidence.reportLocation`, unless it is a frontend weird-bytecode seed reason. `release-readiness.json` v3/v4 mirrors this as `suiteCoverageByBlocker`, machine-readable `missingEvidence`, and summaries for `blockerEvidenceComplete`, `targetEvidenceComplete`, `finalArtifactWritten`, `determinismEvidenceComplete` and `strictModePassed`.

Weird-bytecode seed tests are part of observability: stack permutation, category-2 dup/swap edges, wide/iinc, switch, unreachable blocks, exception-state merge, multi-exit finally, monitor-finally interaction, nested finally and legacy jsr/ret seeds keep `opcode-support-matrix.json` aligned with actual frontend behavior.

## 文档维护

当 rewrite 中出现新的边界、命名、推荐扩展路径或限制时：

- 先更新 `AGENTS.md` 的短规则，方便后续 agent 快速遵守。
- 再更新对应 stage guide，保留更详细的原因和测试落点。
- 如果用户视角行为发生变化，再更新 `README.md`。

不要把内部路线图塞进 README；README 应该保持面向使用者。
