# j2ll Agent Guide

本项目正在进行 clean-room rewrite：旧实现先备份为 legacy reference，新主线从清晰的 compiler pipeline 重新实现。新代码和新测试直接另起根目录 source tree，不继续写进旧 `obfuscator/src`。开始任何重写工作前，先阅读：

- `docs/rewrite-roadmap.md`
- `docs/pipeline/README.md`
- `docs/project-structure.md`
- `docs/java-support-tiers.md`
- `docs/protection-obfuscation.md`
- `docs/io-config-output-contract.md`

## 当前 Rewrite 方向

目标管线：

```text
.class
  -> ASM parse
  -> method CFG
  -> class hierarchy
  -> call graph / runtime analysis
  -> stack bytecode to three-address SSA IR
  -> optimization passes
  -> SSA IR protection passes
  -> LLVM module model
  -> LLVM protection passes
  -> LLVM IR
  -> native link / symbol visibility / strip
  -> output JAR repackaging / native registration
```

旧代码只作为行为参考和测试迁移来源。不要继续在旧的大型 lowering 类上堆新职责。

## Source Tree 边界

- 新生产代码放在根目录 `src/main/java`。
- 新测试代码放在根目录 `src/test/java`。
- 旧生产代码 `obfuscator/src/main/java` 只作为 legacy reference。
- 旧测试代码 `obfuscator/src/test/java` 只作为测试意图和行为样本参考。
- 不在 `obfuscator/src/main/java` 或 `obfuscator/src/test/java` 中添加新架构代码。
- 后续改 Gradle 时，主线 source set 应指向新的 `src/main/java` 和 `src/test/java`；旧 source tree 不进入主线编译，除非显式创建 legacy-only 对照任务。

## Legacy 边界

- 开始删除或移动旧源码前，先创建 legacy backup 分支或 tag。
- 不把 legacy package 放进生产 classpath。
- 不把旧源码复制到新源码树中作为“临时实现”。
- 可以参考旧测试意图，但新测试应落在新的 stage 边界。
- 从旧实现复制小型纯函数前，先确认它属于哪个目标 stage，并补对应测试。

## 推荐扩展路径

- 解析 `.class`：放在 `xyz.melodysky.frontend.classfile`。
- 构建 method CFG：放在 `xyz.melodysky.frontend.cfg`。
- Class hierarchy：放在 `xyz.melodysky.analysis.hierarchy`。
- Call graph / CHA / RTA / devirtualization：放在 `xyz.melodysky.analysis.callgraph` 或 `xyz.melodysky.analysis.runtime`。
- 栈式 bytecode 到 SSA：放在 `xyz.melodysky.ir.ssa`。
- IR model、validator、printer：放在 `xyz.melodysky.ir.*`。
- Optimization pass：放在 `xyz.melodysky.ir.pass`，区分 method pass 和 program pass。
- SSA IR 保护/混淆：放在 `xyz.melodysky.ir.pass.protection`，每个 pass 必须有开关、强度参数和固定 seed。
- LLVM 输出：放在 `xyz.melodysky.backend.llvm`。
- LLVM IR 混淆：必须基于 `xyz.melodysky.backend.llvm.model` / `backend.llvm.protection`，不要做 `.ll` 文本后处理。
- Runtime helper 生成：放在 `xyz.melodysky.runtime`。
- Packaging / repack / native registration：放在 `xyz.melodysky.packaging`。
- Zig/native build orchestration：放在 `xyz.melodysky.toolchain` 或 `xyz.melodysky.zig`。
- Binary export/strip/symbol audit：放在 `xyz.melodysky.toolchain.symbols`；只导出 JNI / C ABI wrapper，Java method internal symbol 默认隐藏。
- 输入/配置/输出契约：按 `docs/io-config-output-contract.md` 实现；selector 命中的每个方法必须记录为 `lowered`、`halfLowered`、`frontendSkipped`、`notApplicable` 或 `failed`，不能静默 skip。
- JVM helper fallback：schema version 1 使用 `nativeEmbeddedClassBlob`，不要退回到明文 generated fallback class。
- 方法改写：普通 class method 可走 `nativeOriginal`；`<init>`、`<clinit>` 和有 Code 的 interface method 必须走 stub/helper 策略；abstract、already-native 和无 Code 的 interface method 记录 `notApplicable`。

如果新增边界或更好的推荐路径，先更新本文件，再更新 `docs/pipeline/` 下对应 stage guide 的详细说明。

## 测试要求

- 添加功能、opcode lowering、runtime helper、LLVM emission、analysis 或 optimization pass 后，必须添加对应测试。
- 添加 opcode lowering：至少补 frontend/SSA 层测试。
- 添加 IR instruction 或 terminator：补 validator 测试和 LLVM backend 测试。
- 添加 runtime helper：补 backend declaration 测试和 runtime stub generator 测试。
- 添加 analysis：补 focused analysis unit test；涉及 invoke/devirtualization 时补 pipeline 测试。
- 添加 optimization pass：补 pass unit test，并确认 pass 后 validator 可通过。
- 改 pipeline 编排：补 pipeline test。
- 改 packaging/native build：补 packaging/toolchain test，必要时再跑 end-to-end benchmark。

测试要跟风险匹配，不要求每个小文档或局部改动都跑完整套件。

## Git 和检查习惯

- 不需要每次写完代码都检查 `git diff` 或 `git status`。
- 在阶段性完成、准备交付、准备提交、或怀疑工作区有并发改动时，再检查 git 状态。
- 不要还原用户未要求还原的改动。
- 不要使用破坏性 git 命令，除非用户明确要求。

## 健壮性原则

- 正确性优先于激进优化；不确定时使用 conservative fallback。
- 每个 stage 应有清楚的 diagnostics、validator 或 focused tests。
- 不让 LLVM backend 修补前端或 IR 的非法状态。
- 不静默忽略 JVM 可见语义，例如 exception、null check、class initialization、monitor、array store check、dynamic dispatch。
- 同一输入应尽量产生稳定排序的 diagnostics、IR、LLVM symbol 和 dump，方便回归测试。

## 文档维护

- 内部架构和 rewrite 计划写在 `docs/`，README 保持用户视角。
- 当实现和文档不一致时，先判断代码是否代表新的真实边界；如果是，更新文档。
- 每次引入新的 stage、fallback 策略、validator、测试落点或不支持边界，都要同步维护本文件和相关 docs。
