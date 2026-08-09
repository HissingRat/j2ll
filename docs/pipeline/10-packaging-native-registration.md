# 10 Packaging And Native Registration

本阶段把 compiler output 重新打包成可运行 JAR，并把 selected target 动态库嵌入到配置指定路径。它只消费 final implementation plan、backend/toolchain artifact 和原 JAR entries，不重新做 bytecode lowering。

## 输入

- original input JAR entries
- final method outcome / rewrite plan
- final method-internalization plan
- native registration plan
- selected target dynamic libraries
- manifest/resource/signature policy
- symbol audit result

## 输出

- final output JAR
- rewritten owner classes
- exactly one generated `<embeddedLibraryDirectory>/Loader.class`
- embedded dynamic libraries
- `reports/lowering-report.json`
- `reports/skipped-method-report.json`
- `reports/packaging-report.json`
- `reports/support-matrix.json`
- `reports/opcode-support-matrix.json`
- `reports/known-blockers.json`
- `reports/release-readiness.json`
- `reports/summary.json`
- JAR preservation summary and signature action report

## 推荐包

```text
xyz.melodysky.packaging
```

推荐类型：

- `Repackager`
- `JarRewriter`
- `MethodRewritePlanner`
- `MethodRewriteStrategy`
- `NativeMethodRewriter`
- `ConstructorStubRewriter`
- `ClassInitializerStubRewriter`
- `InitializerImplementationPlan`
- `ConstructorPrefixPlan`
- `InterfaceMethodStubRewriter`
- `RuntimeLoaderPlan`
- `NativeLoaderClassGenerator`
- `RuntimeLoaderCollisionValidator`
- `NativeRegistrationPlanner`
- `InternalizedMethodClassTransform`
- `InternalizedMethodArtifactVerifier`
- `RegisterNativesTableBuilder`
- `SkippedMethodCollector`
- `SkippedMethodGate`
- `SkippedMethodApproval`
- `OutputJarLayout`

skipped method 的 terminal 展示和确认应由小型、独立的 CLI/pipeline component 完成，不继续把交互、report 和 Zig orchestration 堆进 packaging 或 lowering 大类。

## Final Method Outcome Contract

selector 命中且有 Code 的 method 在 final plan 中只能是：

- `nativeLowered`：拥有经过验证的完整 native implementation。常规形态改写并注册；`internalNativeOnly`形态删除Java declaration与registration。其native语义默认由caller可达的hidden implementation承载，也可在严格coalescing后只保留在唯一caller body中。
- `skipped`：当前不能完整保持语义；保留输入 JAR 中原 Code，不改写为 native，不生成 body helper，不加入 `RegisterNatives`。

不得存在“部分 native、其余从复制字节码执行”的第三种 method 状态，也不得把原 class/method Code 编码后放进动态库。native sources、object graph、最终动态库和 output JAR 都不包含为执行 skipped method 而生成的 class bytes、carrier、decoder 或 hidden-class definition entry。

abstract、already-native、annotation element 和其他 no-Code declaration 使用单独 eligibility evidence。它们没有 executable-method status，不触发 skipped-method confirmation。

build/signing/toolchain/audit failure 是 invocation-level failure，不是 method outcome。schema 不提供 `requiredNative`；所有 skipped method 统一走下面的显式确认。

## Native Implementation Paths

`nativeLowered` 可以由以下完整实现路径承载：

- `LLVM_NATIVE_PATH`：validated SSA/LLVM body 与其 compiler-internal helper closure；受限constructor post-init body与完整supported `<clinit>` body也可通过initializer plan进入该路径。
- `TEMPLATE_JNI_PATH`：从真实 method plan 生成、语义完整且经过测试的 C/JNI body，例如受限interface/String/array/exception bridge。
- JVM/JNI runtime helper-backed LLVM/C body：field、array、allocation、String、reflection、dispatch、monitor、exception 等 Java-visible 语义仍由 JVM/JNI 执行，但 Java method 本身已由 native body 承载，因此仍是 `nativeLowered`。

固定方法名猜测、占位模板、unsupported call-site 或仅有 partial IR 都不能成为 native implementation。final plan 缺少任何所需语义时必须把完整 method 改为 `skipped`。

## Pre-Zig Confirmation

默认 build 必须在 final method outcome 与 native implementation plan 已稳定后、创建任何 Zig workspace 或启动任何 Zig invocation 之前执行一次确认。

行为固定如下：

1. 对全部 `skipped` method 按稳定 method key 排序。
2. 在 stderr/terminal 逐项打印 `owner#name!descriptor`、`reasonCode` 和 reason。
3. 明确警告以上方法不会 native lowered，output JAR 会保留原 Code。
4. 打印 `continue? (Y/N)`。
5. 只有大小写不敏感的显式 `Y` 继续；`N` 或 EOF 终止。

拒绝后不得创建 Zig workspace、不得调用 Zig、不得写 final JAR；reports 必须记录 lowering-stage cancellation、`finalArtifactWritten=false` 和 primary diagnostic/hint。无 skipped method 时不打印列表也不询问。

程序化 pipeline 的无 callback 重载默认拒绝 skipped methods，不能静默等价于 `Y`。Embedding caller 若确实接受保留 Java body，必须显式传入 `SkippedMethodApproval`；`skipped-method-report.json` 用 `confirmationDecision` 区分 `notAnalyzed`、`notRequired`、`approved`、`rejected`、`inputError` 和 `notEvaluatedPriorFailure`。确认输入读取失败使用 lowering diagnostic `SKIPPED_METHOD_CONFIRMATION_INPUT_FAILED`，不得误报为 Zig/toolchain failure。

`--validate` 和 `--dry-run` 不读取 stdin，也不形成 final skipped set。Dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 与 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。显式管道输入 `Y` 必须可用；同一次 invocation 的多次确认必须共享 reader。

TUI 在打印列表和读取输入前必须结束/暂停 active progress region，确认后以新的 region 继续，避免 ANSI 原地刷新覆盖 method list 或 prompt。

## Method Rewrite Strategy

Packaging 只为 `nativeLowered` method 生成 rewrite strategy：

- `nativeOriginal`：ordinary class method with Code。移除 Code、设置 `ACC_NATIVE`，以原 name/descriptor 注册。
- `internalNativeOnly`：仅由final method-internalization plan产生。删除完整Java `method_info`，不注册；默认保留hidden LLVM body与caller实际需要的internal dispatch wrapper。若physical retention为`coalescedNativeOnly`，则callee语义已合并进唯一caller，不再发出独立LLVM body/declaration/reference或generated-C wrapper。
- `constructorStub`：保留从入口到唯一、真实初始化`uninitializedThis`的 `this(...)` / `super(...)` invocation为止的精确Java prefix，包括原descriptor与实参计算；随后调用same-owner private static native body helper承载post-init IR。只接受可唯一识别initializing call的线性prefix，并要求整个constructor没有exception table；不满足时完整constructor为`skipped`。
- `classInitializerStub`：保持或生成 `<clinit>` loader/bootstrap stub，再调用same-owner private static native body helper承载完整、经final plan验证的initializer IR。
- `interfaceMethodStub`：保持 interface method 合法字节码，并调用拥有 native method 的 generated helper。

`internalNativeOnly`只消费analysis/final planner已经批准的immutable plan，不在packaging阶段重新推断world或observer边界。该plan可包含declared/current-JAR scope下的exact allowlisted public static，以及仅declared `CLOSED_WORLD`下、所有调用点same-owner exact的public instance；后者不要求method/class为final。resolved exact observer在进入packaging前必须已拒绝，unsupported/unbounded reflection/JNI/agent风险只保留warning/report evidence，不能被packaging擅自升级或静默丢弃。Coalescing使用另一个immutable physical-retention plan；packaging仍删除同一个MethodNode，LLVM compiler、C binding filter与artifact audit共同证明callee standalone surface消失。

`<init>`、`<clinit>` 和 interface method 不能强制使用 `nativeOriginal`。`skipped` method 不生成任何 strategy；no-Code declaration 只保留 eligibility evidence。

Initializer planner产出的prefix/native-body boundary必须被LLVM compiler、rewriter、registration和artifact audit共同消费；packaging不能重新扫描并猜一个不同的constructor split。Constructor helper显式接收`this`与原参数，`<clinit>` helper为无参数static native body。

## Loader And Registration

每次 build 恰好生成一个 Java 17 class：

- internal name：`<embeddedLibraryDirectory>/Loader`
- JAR entry：`<embeddedLibraryDirectory>/Loader.class`

Loader 只承担：

- 为当前 OS/arch 选择 embedded native library。
- 解压到 per-classloader content-addressed temp/cache path。
- 在 `System.load` 前校验 SHA-256。
- 触发 exported bootstrap/JNI registration。
- final field plan 有 internalized reference/array slot 时，通过 `ClassValue<Object[]>` 缓存 per-defining-Class sidecar。

Loader 不定义或执行复制的 class/method bytecode，也不包含 hidden-class definition API。没有 reference/array sidecar 时，不生成相关工具方法。

Namespace and collision rules：

- `embeddedLibraryDirectory` 同时是 native-resource prefix 和 Loader JVM package prefix，必须匹配 `[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*`；`java[/...]` 和 `META-INF[/...]` 保留。
- input base entry 与 generated Loader 同名时，以 `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION` 在 Zig 前失败。
- 任意 `META-INF/versions/**/<embeddedLibraryDirectory>/Loader.class` shadow 以 `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW` 在 Zig 前失败。
- 同一 defining `ClassLoader` 中加载多个使用相同 directory 的不同产物存在同名 Loader 边界；应用应选择唯一目录。独立 ClassLoader 的 Loader state 相互隔离。

Registration rules：

- ordinary class methods register against their owner class。
- constructor/class-initializer body helpers register as same-owner private static native helpers。
- interface helpers register against generated helper classes。
- `internalNativeOnly` methods不进入registration plan；其owner/name/descriptor不会因该target单独进入`JNINativeMethod[]`。
- tables 按 registration owner 分组；同一 build identity 保持 deterministic，正式 randomized build 对 ordinary owner-local method order 和 owner order 做 build-scoped 重排，hidden 模式遵从其独立 protection physical order。
- `skipped` 和 no-Code declarations 都没有 binding。
- owner lookup不得在helper注册前触发selected owner `<clinit>`。`JNI_OnLoad`只对唯一generated Loader anchor调用JNI `FindClass`，从它取得exact defining `ClassLoader`；business owner的slash name在activation scratch内原位转为binary name，再由`Class.forName(name, false, definingLoader)`解析。不得对business owner调用`FindClass`，也不得回退TCCL或system loader。
- owner name 在 defining-loader class lookup 返回后立即清零；method/descriptor scratch 只存活到该 owner 的 `RegisterNatives` 完成。多 owner 注册必须是原子的；任一后续 `RegisterNatives` 失败时逆序 `UnregisterNatives` 已成功 owner，清理 retained local refs/scratch。只有每次 unregister 都返回 `JNI_OK` 且没有 pending exception，而且恢复原异常的 `Throw` 返回 `JNI_OK` 并形成 pending exception时，才返回普通 `JNI_ERR`；rollback 或 exception-restore 的 status/evidence failure 通过编码错误文案的 `FatalError` fail closed，避免保留悬空 native binding或静默丢失原异常。

loader state是per classloader的四态fail-closed状态机：`UNLOADED`在调用`System.load`前先转为`LOADING`；只有`System.load`及其中的`JNI_OnLoad`完整返回才转为`READY`。同线程重入看到`LOADING`时必须在第二次load前失败；任何Throwable把状态转为`FAILED`并原样向首次调用方传播，后续调用不重试、不伪装ready。并发线程仍由Loader monitor串行化。失败抛出包含稳定load-state或target/class context的`UnsatisfiedLinkError`。

## Field Internalization

Packaging 只删除 final field plan 批准且结构仍匹配的 field。每一个实际 accessor 必须同时满足：

- final method outcome 是 `nativeLowered`。
- final implementation path 使用 field plan 认可的 native storage ABI。
- generated LLVM/C 中没有 raw JVM field access marker。

任何 accessor 为 `skipped`、缺少 implementation、仍走普通 JVM field ABI 或未通过 final validation 时，该 field 都保留在 classfile。

approved primitive slot 使用 per-defining-`jclass` weak-keyed raw-bit storage；approved reference/array slot 始终留在 JVM heap，通过 Loader 的 `ClassValue<Object[]>` 和 JNI ObjectArray API 访问。不得通过 skipped method 或复制字节码访问 sidecar。

## Method Internalization

Packaging只消费final immutable method plan，不重新推断call graph。对每个approved method：

- 结构必须仍精确匹配owner/name/descriptor/access与ordinary Code-bearing method kind。
- ordinary rewrite先完成，随后`InternalizedMethodClassTransform`删除精确的`MethodNode`；缺失、重复或形态漂移以`METHOD_INTERNALIZATION_REWRITE_FAILED`阻止final JAR。
- final registration plan必须已经过滤该binding，native implementation与caller route仍必须存在。
- packaged JAR通过`InternalizedMethodArtifactVerifier`递归扫描method declaration、`MethodInsn`、LDC method Handle、invokedynamic/ConstantDynamic bootstrap和`EnclosingMethod` metadata；任一残留是blocking artifact-audit failure。

V1支持private/protected static（包括cross-owner protected static caller）与same-owner exact private/protected instance method。后者不能通过receiver runtime class伪造dispatch；cross-owner instance/interface/non-exact virtual均保留普通Java native declaration。

## Managed Zig Build

selected-target native build 只走 managed Zig `0.15.2`：

- source graph 包含 final per-class `.ll`、Zig-managed `.o`、JNI wrapper C 和 runtime helper C。
- source graph 不包含原 method/class Code 的可执行副本。
- 一个 generated `build.zig` 和一次 matrix-wide `zig build` 负责全部 selected targets。
- Java 侧不增加 host `cc` / `clang` / `zig cc` 直连路径。
- required target 的 capability、preflight、compile 或 link 失败报告 `ZIG_TARGET_UNBUILDABLE`，阻止 final JAR。

固定结构性矩阵是 Windows GNU x86_64/AArch64、Linux GNU glibc 2.17 x86_64/AArch64 和 macOS 10.15 x86_64/11.0 AArch64。cross-link success 不等于 non-host OS/JVM runtime E2E。

最终 workspace 动态库扁平写入 `native/<library-file-name>`；JAR resource 独立写入 `<embeddedLibraryDirectory>/<library-file-name>`。

## JAR Preservation

output JAR 必须保持可运行：

- preserve manifest main attributes，除非 j2ll 明确拥有某个 `J2LL-*` attribute。
- preserve `Main-Class`、agent attributes、`Automatic-Module-Name` 和 `Multi-Release`。
- preserve non-class resources 和 `META-INF/services/*`。
- preserve `module-info.class`。
- preserve `META-INF/versions/**`；versioned class lowering 是单独 policy。
- selected base Code-bearing method 的 owner 有 versioned counterpart 时，该 method 记录为 `skipped` + `MULTI_RELEASE_VERSIONED_CLASS`，保留原 Code、不注册，并进入默认 build 的 skipped-method confirmation。只有 abstract/already-native/其他 no-Code declaration 使用独立 eligibility evidence。

signed input handling 服从 `docs/io-config-output-contract.md` 中的 `signaturePolicy`。config、signing、toolchain、artifact audit 或 readiness failure 都必须让 `finalArtifactWritten=false`。

## Validator

Packaging validator 至少检查：

- 每个 `nativeLowered` method 都有且只有一个 validated logical implementation和rewrite；ordinary/stub形态有唯一registration binding，`internalNativeOnly`则必须无Java declaration、无binding且仍有native caller closure。`coalescedNativeOnly`由caller body承载该closure，并额外要求callee standalone LLVM/C/workspace residual为零。
- 每个constructor stub保留的prefix与final initializer plan逐指令一致，helper call只出现在初始化调用之后；每个`<clinit>` stub只保留loader/bootstrap与native helper调用，不复制原initializer body。
- 每个 `skipped` method 保留原 Code，且没有 rewrite/helper/binding。
- no-Code eligibility evidence 不进入 executable method counts 或 confirmation。
- selected target libraries 全部存在且非空。
- output JAR 恰好有一个正确名称、Java 17 version 的 Loader。
- Loader 始终包含 native loading；reference sidecar 只在 final plan 需要时存在。
- input base/MR Loader collision 在 Zig 前拒绝。
- native source/artifact 中没有为执行 skipped method 而保存的 class bytes 或 decode/define machinery。
- internalized field 的全部 accessor 都是 final `nativeLowered` 且 storage ABI 合格。
- internalized method 在JAR中没有declaration/invocation/Handle/bootstrap/EnclosingMethod残留，在native closure中仍有hidden implementation与所需wrapper。
- generated wrapper、LLVM function、bootstrap identifier 是 deterministic hash-only token，dynamic exports 精确匹配 allowlist。
- manifest/resource/signature policy、target artifact SHA-256、report manifest 和 artifact audit 一致。
- output JAR entry ordering deterministic。

## 测试

- ordinary `nativeOriginal`、带参数`this(...)`/`super(...)` verifier prefix + post-init constructor helper、完整class initializer helper和interface helper rewrite。
- unsupported method -> `skipped`，原 Code byte-for-byte/semantic preservation，且无 native binding。
- no-Code selector match -> eligibility evidence，且不触发 confirmation。
- skipped 列表稳定排序、reason 输出、Y continue、N/EOF abort、invalid input 重试。
- piped Y 与共享 reader 的多确认测试。
- rejection 发生在任何 Zig workspace/invocation 前，且无 final JAR。
- validate/dry-run 不读 stdin，也不形成 final skipped set；dry-run 写入 deferred/conditional confirmation 字段。
- Loader identity/version/exactly-one-entry、minimal method set、optional ClassValue sidecar。
- fieldInternalization final accessor gate。
- methodInternalization protected/public static在declared/current-JAR scope下的cross-owner route、same-owner protected/public-instance exact dispatch、非final public instance、resolved exact observer拒绝、unbounded observer风险report、class transform与residual audit。
- manifest/resource/service/MR/module-info preservation。
- signed input `fail`、`strip`、`resign`。
- six-target artifact/export/privacy audit 与 host child-JVM differential。当前新增initializer路径的真实运行证据仅覆盖Windows real-Zig host；其他target不以cross-link证据代替JVM runtime parity。
