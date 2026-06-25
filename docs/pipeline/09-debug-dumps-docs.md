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
intermediates/dumps/fallback/*.json
intermediates/dumps/llvm-model/*.json
intermediates/dumps/llvm-protection/*.json
intermediates/dumps/llvm/*.ll
intermediates/dumps/native-link/*.json
intermediates/dumps/symbol-audit/*.json
intermediates/dumps/packaging/*.json
```

dump 输出不是用户主界面，但对 rewrite 和回归定位非常重要。默认可关闭，debug/analyze 模式打开。

## Dump 原则

- 稳定排序。
- 不依赖对象 identity。
- 包含 stage name 和 artifact id。
- 不泄露不必要的大量 binary 数据。
- 适合被 test fixture 对比。

## 文档维护

当 rewrite 中出现新的边界、命名、推荐扩展路径或限制时：

- 先更新 `AGENTS.md` 的短规则，方便后续 agent 快速遵守。
- 再更新对应 stage guide，保留更详细的原因和测试落点。
- 如果用户视角行为发生变化，再更新 `README.md`。

不要把内部路线图塞进 README；README 应该保持面向使用者。
