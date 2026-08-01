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
  -> FinalNativeImplementationPlan
  -> MethodInternalizationPlan
  -> SkippedMethodConfirmation
  -> ZigNativeBuildAndSymbolAudit
  -> Repackager
```

`SkippedMethodConfirmation` 是 CLI/build boundary，不是 compiler pass。它只在 final implementation plan 已确定且即将进入 Zig 时运行，避免前端 provisional decision 与最终可注册方法集合不一致。

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
11. [`10-packaging-native-registration.md`](10-packaging-native-registration.md)：JAR rewrite、loader、native registration、skipped-method preservation。
12. [`11-tier5-runtime-metadata-reflection-jni-unsafe.md`](11-tier5-runtime-metadata-reflection-jni-unsafe.md)：runtime metadata、static reflection、JNI ABI、MethodHandle/ConstantDynamic、Unsafe/VarHandle 前置层。

## Method Outcome Contract

selector 命中的 Code-bearing method只有两个最终 method outcome：

- `nativeLowered`：原业务语义由已验证的 LLVM、生成式 template/stub 或 JNI/runtime helper-backed native implementation 完成。普通Java入口具有Code rewrite与`RegisterNatives`证据；批准为`internalNativeOnly`的方法具有LLVM body/native-caller closure，output class不再含method_info且不注册。
- `skipped`：原 method/classfile 形态保持不变，不生成 native body，不进入 registration，也不在 native artifact 中复制 bytecode。

`excluded` 只描述 selector/blacklist 之外的方法。abstract、already-native、无 Code interface declaration/annotation element 等 selector 命中只进入 eligibility evidence，无 method status且不触发 confirmation。parse、validation、toolchain、packaging 或 audit failure 是 build-level status，不是第三种 method outcome。带 Code 的 selected base method若存在 multi-release counterpart，则为 `skipped` + `MULTI_RELEASE_VERSIONED_CLASS` 并进入 gate。

Schema v1 不提供 `requiredNative`。如果 final plan 含 skipped methods，default build 在创建 Zig workspace/调用 Zig 前：

1. 在 stderr 按稳定顺序逐条打印 method identity、reason code 和 reason。
2. 明确说明这些方法不会 native lowered，原 Java bytecode 会保留。
3. 提示 `continue? (Y/N)`；只有显式 `Y` 继续，`N`/EOF 终止。

重定向/CI 不能绕过该 gate，piped `Y` 是正式自动化方式。`--validate` / `--dry-run` 不读取 stdin，也不形成 final skipped set；dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 与 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。

## 当前语义快照

- SSA merge 使用 block parameters。受保护JNI/runtime helper site还显式携带pending exception value、按classfile顺序排列的typed/catch-all handler和throw-site live locals；throwable与locals通过exception edge arguments进入handler。无法形成一致exception frame或涉及尚未支持的monitor/finally交互时，整个 selected method 才变为 `skipped`。
- LLVM direct path 与 JNI/runtime helper-backed path 都属于 `nativeLowered`。helper 可以调用 JVM API、执行普通 JVM dispatch、维护 pending exception/reference lifetime；受保护helper site会立即读取并清除pending exception、按序匹配typed/catch-all handler，未匹配时rethrow。selected caller 的原 bytecode不会被复制或重放。
- JDK String/StringBuilder/System.arraycopy/Math/boxing/Objects、env-backed `Object.getClass()`、JVM-backed `Thread.sleep(J)V`、受限 reflection、Unsafe/VarHandle、MethodHandle/lambda、field/array/allocation/type/monitor/exception/call dispatch 等已实现 subset继续走 helper-backed native lowering。超出经过验证 descriptor/shape matrix 的 caller 变为 `skipped`。
- Constructor只把精确的线性verifier prefix保留在Java stub中：真实 `this(...)` / `super(...)` 调用及其原实参完成后，post-init body进入same-owner native helper。`<clinit>`保留loader/bootstrap stub，完整initializer body进入native helper；无法安全分割或最终native plan不完整的initializer变为 `skipped`。
- 唯一 runtime class 是 Java 17 `<embeddedLibraryDirectory>/Loader.class`。它只负责 native library 选择、SHA-256 校验、加载和注册，并在 field internalization 实际需要 reference/array slot 时按需加入 `ClassValue<Object[]>` sidecar。
- runtime/source/package 中不再存在 bytecode-preserving helper class、embedded class blob、blob carrier/decoder 或 hidden-class define API。
- `fieldInternalization` 只批准所有真实 accessor 最终均为 `nativeLowered` 且符合 storage ABI 的字段。任一 accessor 为 `skipped`、unselected、cross-owner 或存在动态观察边界时保留 JVM field；reference/array 状态仍由 Loader 的 JVM-managed `ClassValue<Object[]>` sidecar持有。
- `methodInternalization`在final LLVM plan后运行，通常批准private/protected static或same-owner exact private/protected instance method。required exact `publicMethodInternalizationAllowList`可另行授权public static及same-owner exact public instance：public static可使用declared `CLOSED_WORLD`或本次Y授权的current-JAR-only scope；public instance只接受declared `CLOSED_WORLD`及parse-complete input+classPath world，不要求method/class为final，也不因可覆写slot本身拒绝，但每个调用点必须exact且caller仍须same-owner。已解析的exact reflection/MethodHandle/Handle/bootstrap/ConstantDynamic/EnclosingMethod observer或已知external Java entry会保留入口；无法穷举的reflection/JNI/agent动态观察面作为allowlist/world授权接受的风险进入warning/report。批准项仍为`nativeLowered`，但Java declaration与`RegisterNatives` binding被移除；cross-owner static和reference-returning caller通过nested-local-frame internal dispatch bridge进入hidden native wrapper，same-owner direct scalar路径可继续使用LLVM direct ABI。
- managed Zig `0.15.2` 通过一次 matrix-wide invocation 构建 selected targets；source set 只含 per-class LLVM、JNI wrapper C 和 runtime/helper C。六目标结构性交叉产物证据与 non-host JVM runtime E2E 继续分开。
- 本轮新增的protected pending-exception与initializer真实运行证据当前只覆盖Windows real-Zig host child JVM；`Object.getClass()`与`Thread.sleep(J)V`当前只有focused planner/LLVM/C ABI evidence，其他目标只可引用结构性交叉构建证据。
- artifact audit 验证每个 `nativeLowered` method 的native artifact closure：registered入口有wrapper/registration，`internalNativeOnly`入口没有Java declaration/registration且所有MethodInsn/Handle/bootstrap/EnclosingMethod residual为零；每个 `skipped` method 的原 body 保留且无 registration，fallback bytecode复制表面在 generated C/native/JAR 中不存在。
- release-readiness/support/opcode/known-blocker reports 用 `nativeLowered`/helper-backed evidence和精确 `skipped` reason表达覆盖。长期工作按 reason code逐项扩大 native support，目标是持续减少 skipped methods。

## 维护规则

- 新增 stage、helper policy、skipped boundary、validator、测试落点或目录边界时，先更新 `AGENTS.md`，再更新对应 stage guide。
- README 保持用户视角；内部 rewrite 计划和 compiler 设计只放在 `docs/`。
- 单个 stage guide 应保持 focused。跨阶段规则放在本索引、`00-overview.md` 或 `08-diagnostics-validation-testing.md`。
- 不把 skipped confirmation、console formatting 或 stdin handling塞进 lowering/backend 大类；保持独立 notice/confirmation 组件并通过小接口连接 final plan。
