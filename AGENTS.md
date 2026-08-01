# j2ll Agent Guide

本项目正在进行 clean-room rewrite：旧实现只作为 legacy reference，新主线从清晰的 compiler pipeline 重新实现。开始重写工作前完整阅读：

- `docs/rewrite-roadmap.md`
- `docs/pipeline/README.md`
- `docs/project-structure.md`
- `docs/java-support-tiers.md`
- `docs/protection-obfuscation.md`
- `docs/native-hardening-attacker-validation.md`
- `docs/io-config-output-contract.md`

## 产品与 JVM 边界

- j2ll 是 JVM-hosted JAR 混淆/native-lowering 工具；输出始终是需要 Java 17+ JVM 的 JAR。
- Java object、array、Class、String、Throwable、Thread、monitor、object identity与 GC lifetime均属于 JVM。
- Native-lowered code只通过 JNI/runtime helper操作 Java-visible value。`new`、array allocation、reflection/lambda construction和 `Unsafe.allocateInstance` 必须走 JVM/JNI API。
- `alloca`/native heap只能保存 native临时数据，不能伪造、长期保存或返回 Java object。
- 不引入 standalone/native-image、自有 object model/GC/thread scheduler路线。

目标管线：

```text
.class
  -> ASM parse
  -> method CFG
  -> class hierarchy
  -> call graph/runtime analysis
  -> three-address SSA IR
  -> optimization/protection
  -> LLVM module model/protection
  -> LLVM IR
  -> final native implementation plan
  -> skipped-method confirmation
  -> Zig build/link/symbol audit
  -> JAR rewrite/packaging/registration
```

## Source Tree 与 Legacy

- 新生产代码只放 `src/main/java`，新测试只放 `src/test/java`。
- `obfuscator/src/main/java`、`obfuscator/src/test/java` 和 `obfuscator/bench` 只读参考，不进入主线 classpath。
- 删除/移动 legacy前先有可恢复 branch/tag。
- 不把 legacy package或大型 lowering class复制进新 tree；允许复用的纯函数先归属明确 stage并补 focused test。
- 避免 giant class。参数解析、notice/confirmation、report serialization、runtime source generation和 stage policy都拆成职责单一组件。

## Method Outcome 与 Unsupported Boundary

- selector命中的 Code-bearing method最终只有 `nativeLowered` 或 `skipped`。
- `nativeLowered`：原业务语义由经过验证的 LLVM、生成式 template/stub或 JNI/runtime helper-backed native implementation完成。Java入口保留时原 Code按合法策略替换且native body/registration都有证据；经method-internalization批准的internal-only method则必须有native body、native caller closure和无Java declaration/registration的证据。
- `skipped`：原 method/classfile形态保留，不生成 native body/wrapper，不进入 `RegisterNatives`，native/JAR中不保存第二份 method bytecode。
- `excluded`只描述 selector/blacklist外方法。abstract、already-native、无 Code interface declaration/annotation element等 selector命中只记录 method-eligibility evidence，不产生 method status，也不进入 skipped confirmation gate。
- parse/validation/toolchain/packaging/audit failure是 build-level status，不是额外 method outcome。
- 不允许 silent skip。`lowering-report.json`和 `skipped-method-report.json`必须覆盖每个 selected result。
- Schema v1不增加 `requiredNative`；接受 skipped methods由每次 default build的确认 gate决定。
- Runtime/JNI helper-backed调用仍属于真实 `nativeLowered`。只有无法由 direct LLVM、生成式 native body或 approved helper/dispatch完整表达语义时，才跳过整个 caller。

## Skipped-Method Confirmation

- Final implementation plan形成后、创建 Zig workspace或调用 Zig前，default build检查 skipped methods。
- 若存在 skipped method，stderr按稳定顺序逐条打印 `<owner>#<name>!<descriptor>`、reason code和 human-readable reason。
- warning必须明确说明这些方法不会 native lowered，原 Java bytecode会留在 output JAR。
- 提示 `continue? (Y/N)`；只有显式、大小写不敏感的 `Y`继续。`N`、EOF或无可读输入终止，不创建 Zig workspace、不调用 Zig、不写 final JAR。
- Piped stdin是正式自动化入口；非 TTY/CI不得绕过确认。
- `--validate`/`--dry-run`不读取 stdin，也不形成 final skipped set；dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 与 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。
- programmatic pipeline 无 `SkippedMethodApproval` callback 的重载必须默认拒绝 skipped methods；确认结果写入 `skipped-method-report.json`，stdin/callback 读取异常用 lowering diagnostic，不能映射成 Zig failure。
- 将列表收集、approval policy、格式化与 stdin policy放在独立 `SkippedMethodCollector`/`SkippedMethodGate`/`SkippedMethodConfirmation`/input adapter中；不要塞进 `J2llCli`、lowerer、pipeline giant class或 Zig builder。

## 推荐扩展路径

- class parse：`xyz.melodysky.frontend.classfile`
- CFG：`xyz.melodysky.frontend.cfg`
- hierarchy：`xyz.melodysky.analysis.hierarchy`
- call graph/runtime/reflection/field：`xyz.melodysky.analysis.*`
- SSA/model/validator/pass：`xyz.melodysky.ir.*`
- LLVM model/emission/protection：`xyz.melodysky.backend.llvm.*`
- runtime/JNI helper：`xyz.melodysky.runtime.*`
- packaging/rewrite/registration：`xyz.melodysky.packaging`
- Zig/native build：`xyz.melodysky.toolchain`
- binary inspectors/export audit：`xyz.melodysky.toolchain.symbols`
- reports/config：`xyz.melodysky.report` / `xyz.melodysky.config`

每个 stage只消费稳定 artifact，不回读上层 mutable builder state。ASM只留在 frontend；backend不修复非法 SSA、不重新推断 Java语义；LLVM protection只操作 module model，不做 `.ll` regex。

## Field Internalization

- `fieldInternalization`已进入 schema，默认 `false`；只在 declared `CLOSED_WORLD`或本次 build明确 Y授权 current-input-JAR-only scope时分析。
- Current-JAR-only授权不改写 configured `worldModel`、不解析 configured `classPath`，必须进入 diagnostics/report。N/EOF在 workspace/pipeline/Zig前退出；validate/dry-run只记录 `confirmationRequired`。
- 候选仅限 input-base `private static` primitive/reference/array field；instance field、volatile/final、ConstantValue、field-owning `<clinit>` access、multi-release与 reflection/serialization/JNI/Unsafe/VarHandle/MethodHandles Lookup/agent observation边界保留 JVM field。
- 每个真实 accessor必须是same-owner static或instance method，最终为`nativeLowered`且 final implementation path为支持对应storage ABI的`LLVM_NATIVE_PATH`。任一cross-owner、unselected、`skipped`或non-LLVM accessor都保留JVM field；不存在bytecode-accessor rewrite path。
- Primitive使用 per-defining-`jclass` weak-keyed relaxed atomic raw bits，按 descriptor执行 boolean low-bit、窄整数截断/扩展和 float/double bitcast。
- Reference/array始终留在 JVM heap，由唯一 Loader按需加入 `ClassValue<Object[]>` sidecar强持有。`ClassValue`是跨调用 cache；native activation首次实际访问时惰性获取 local ref、复用并在退出时释放，不建立 native strong global ref。
- Native instance wrapper向field sidecar传递method/field的declared defining `jclass`，不得用`GetObjectClass(self)`按receiver runtime subclass分裂static storage。
- Final plan、IR slot rewrite、FieldNode removal和 residual declaration/instruction/Handle/bootstrap audit共用同一 approved plan并 fail closed。

## Method Internalization

- `methodInternalization`是直接boolean，默认`false`；declared `CLOSED_WORLD`可分析private/protected和exact allowlisted public候选，本次build明确Y授权的current-input-JAR-only scope可分析private/protected及exact allowlisted public static。授权是feature-scoped，不改写`worldModel`；current-JAR-only不读取configured `classPath`，并把JAR外caller/subclass/reflection/JNI/agent observer明确记为用户接受的范围外风险。
- `publicMethodInternalizationAllowList`是required `array<string>`、示例默认`[]`；每项必须是无wildcard、无重复的exact `<owner>#<name>!<descriptor>`。public static可使用declared `CLOSED_WORLD`或本次Y授权；public instance只允许configured world为declared `CLOSED_WORLD`且input+全部configured classPath形成parse-complete hierarchy/call world时分析。combined world缺失任一superclass/interface时，该public instance候选以`METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE`保留；不得因此整批禁用public static/private/protected候选。
- V1候选仅限已有final `LLVM_NATIVE_PATH`的ordinary Code-bearing method：支持private/protected static（static caller可cross-owner）和same-owner exact private/protected instance；exact allowlist另可授权public static及same-owner exact public instance。public instance不要求method/class为final，也不因存在可覆写slot本身拒绝，但scope内每个调用点都必须exact解析到候选且caller仍须same-owner；实际导致non-exact dispatch的override会拒绝该候选。已解析的exact reflection/MethodHandle/Handle/bootstrap/ConstantDynamic/EnclosingMethod observer、launcher/agent entry，以及closed exact catalog识别到的JVM/JDK callback（包括Object虚方法、Runnable/Callable、线程/定时器、序列化和常见`java.util.function`合同）一律保留Java method；catalog只按真实hierarchy subtype/implements关系与exact descriptor匹配，不恢复blanket override-slot veto。catalog外第三方framework callback与无法穷举的reflection/JNI/agent动态观察面仍是allowlist加world授权显式接受并进入warning/report的风险。cross-owner instance、interface、constructor、class initializer、synchronized、bridge/synthetic、multi-release、零native caller或任一非LLVM caller同样保留。
- Approved method仍是`nativeLowered`，rewrite strategy为`internalNativeOnly`；其LLVM body保留并以hidden linkage进入final link，但output class删除整个method_info，且不进入`RegisterNatives`。所有ordinary Java/Handle/bootstrap/EnclosingMethod residual必须在final JAR audit中为零。
- same-owner direct scalar call继续使用validated LLVM direct ABI。cross-owner static和exact same-owner instance dispatch使用binding-local internal bridge；bridge不得执行`GetMethodID`/`Call*MethodA`，而是进入hash-only internal wrapper。reference/owned/pending-exception target必须使用nested JNI local frame：`PushLocalFrame`，descriptor-aware参数解包，normal reference result经`PopLocalFrame(result)`提升；pending exception需清除、跨frame提升并严格恢复，失败`FatalError`。
- Analysis、final-plan validator、registration filter、generated-C bridge、MethodNode removal、lowering/packaging report与artifact residual audit必须消费同一immutable approved plan并fail closed。

## Runtime Loader 与 Packaging

- 唯一 runtime class是 Java 17 `<embeddedLibraryDirectory>/Loader.class`。
- Loader只包含 target选择、SHA-256校验、extract/load/register，以及 field plan需要时的 `ClassValue<Object[]>` sidecar；不得包含 hidden/generated class definition或 embedded-bytecode decode API。
- 不输出旧 runtime support class、companion/nested Loader或 artifact-specific NativeLoader。
- `embeddedLibraryDirectory`同时是 resource与 Loader package prefix，必须为规范 Java internal package path；input base/MR同名 Loader在 Zig前分别以稳定 collision reason失败。
- 普通 Code method可用 `nativeOriginal`；`<init>`、`<clinit>`和有 Code interface method使用合法 stub + generated native body helper。无法把全部用户语义移入 native implementation时整个 method为 `skipped`。
- Packaging只重写 `nativeLowered` methods：registered入口按既有native/stub策略重写，`internalNativeOnly`精确删除method_info；`skipped` methods精确保留并验证没有registration/native bytecode copy。
- `JNI_OnLoad` registration owner lookup直接用 slash internal name调用JNI `FindClass`，利用发起`System.load`的defining-loader context；不得把TCCL作为registration resolver。owner name必须在class lookup返回后立即清零。多 owner registration必须原子；单个 owner 的 `RegisterNatives` 失败也必须先对当前 owner 执行严格回滚，再把原异常交给外层逆序`UnregisterNatives`此前成功owner并清理local refs/scratch。只有每次unregister都返回`JNI_OK`且无pending exception、恢复原异常的`Throw`返回`JNI_OK`并形成pending exception时才允许返回普通失败，否则`FatalError` fail closed。
- 保留 manifest/resources/services/module-info/multi-release entries。Base class有 versioned counterpart时，命中 method为 `skipped` + `MULTI_RELEASE_VERSIONED_CLASS`，不 rewrite/register。
- Signed JAR：`fail`在 rewrite前拒绝；`strip`移除 signature entries并 warning；`resign`先 preflight keystore/password/alias，再用当前 JDK `jarsigner`。失败不保留 final JAR。

## JVM 语义边界

- SSA merge使用 block parameters，terminator携带 target arguments；live-in mismatch或未建模 throw-site local frame把整个 method标记 `skipped`。
- Typed catch、exception edge、显式 `athrow`和 implicit exception sites必须显式建模。Unprotected JNI pending exception立即走descriptor-safe return并保留pending state；protected site先清除pending state，再按classfile顺序dispatch typed/catch-all handler。缺少pending/handler-transfer evidence、复杂 finally/state merge/monitor interaction未支持时跳过整个 method，不能继续执行 pending exception。
- Method inlining 只能删除 direct-call 的无 handler synthetic pending-exception evidence：callee 必须已证明为 pure/non-throwing，site 没有 handler，exception value 没有任何 use。protected edge、specific exception kind或observable exception value必须保留并跳过该 inline candidate。
- Monitor/synchronized/volatile/final/thread happens-before使用 JVM/JNI helper/marker；`Thread.sleep(J)V`通过JVM-backed helper执行并保留`InterruptedException`语义。不伪造 scheduler或 monitor queue，未支持的其他Thread/wait-notify caller为 `skipped`。
- Class init active-use guard与 `<clinit>` begin/end/failed helper必须保持 JVM ordering。
- JDK/reflection/MethodHandle/lambda/Unsafe/VarHandle只有 validated direct/helper/dispatch matrix算 `nativeLowered`；超出 matrix的 selected caller为 `skipped`。
- JNI helper返回的owned local ref必须有可证明的activation/last-use lifetime。backend消费per-method ownership/release plan；site-sensitive liveness必须区分normal live-out与instruction exceptional needs，并在普通边、parallel edge adapter、loop/backedge、typed/catch-all handler与显式`athrow`路径发出`DeleteLocalRef`。重复ownership transfer、handler live-set不一致或其他无法证明有界释放的shape将整方法`skipped`并记录`UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME`。registered native callee只要返回reference或内部产生owned/pending-exception reference，就必须走JVM/JNI bridge获得嵌套local frame；只允许不产生这类reference的callee直接LLVM调用，compiler-internal callee无法安全桥接时fail closed。JNI bridge的`jvalue[]` scratch按function最大arity只在activation prologue分配一次，loop/backedge只复用；不能依赖helper/internal callee返回、递归展开或循环迭代自动释放local ref。
- Unsafe offset是 metadata token，不是 native object memory offset；不绕开 JVM读取 object layout。

## Protection

- IR/LLVM pass字段是直接 boolean；`protection.seed=null` 的正式 build 使用随机 build identity，
  显式 seed 才进入 reproducible 模式。各 stage 必须从 domain-separated identity 派生材料；
  schema v1不提供 strength/intensity。
- Build identity只接受`BuildProtectionDomain`闭集，不允许生产调用方传ad-hoc字符串domain；
  mainline通过集中derived-material plan消费IR method/program、field、business string、
  method table、wrapper、LLVM symbol/pass、native text与registration独立域。
- `fakeBranches`、`basicBlockSplitting`、`blockNameObfuscation`是独立 pass。LLVM visibility/configurable hardening与 mandatory hidden linkage/export audit分开。
- `ControlFlowFlatteningPass` 的 dispatcher state 使用 per-build、per-method 派生的
  dense permutation，状态集合始终为 `[0, blockCount)`；不得为了多样性扩张状态空间、
  引入查表或增加运行时 transition work。
- CFF会引入synthetic dispatcher cycle，因此对其余structural条件原本可应用、但会产生
  owned JNI local ref的方法必须pass-level `SKIPPED`并记录
  `CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE`，保留该pass输入IR供后续native lowering；
  这个protection skip不得改变method outcome。不得为了提高CFF覆盖而绕过ownership/release proof。
- Final `LLVM_NATIVE_PATH`与 compiler-internal helper只由 `NativeLlvmCompiler`编译一次；reports、intermediates和 Zig writer共用同一 validated module/pass result。
- Protection pass对单 method不适用只记录 pass `SKIPPED` reason，不自动改变 method outcome；compiler/runtime implementation无法保持语义才产生 method `skipped`。
- Protection coverage必须由producer逐method或真实module subject显式写`requested/applicability/affected/status/reasonCode`；function pass只按`affectedFunctions`映射，module/global pass不得把一个global变化扩写成所有method affected，validation failure无法确定逐method applicability时写`unknown`。collector不得从汇总`SKIPPED`或旧`affectedMethods`推断。
- Generated identifiers使用 hash-only token；JNI必要 owner/member/descriptor/error metadata在 generated C中 encoded at rest。Report不写 raw seed或 plaintext。
- `StringEncryptionPass`的新产出固定为`enc:v2`：carrier数值token必须与
  encrypted-payload key绑定，token SSA名称与数值都由build/method/site材料派生；
  `enc:v1`只允许作为compiler-internal兼容读取边界，不能重新成为生产emission。
- 通用 runtime metadata、business string与registration text分别消费独立
  build material。Native text按build/purpose/use派生site-bound codec family与
  schedule并内联到owning activation；不得恢复统一decoder、固定全局codec shape
  或相邻XOR seed-share/cipher。Generated-C gate必须阻断decoder fanout、
  fixed-shape和adjacent-seed回归。
- 多字节native-text ciphertext按build/purpose/use派生的affine bijection物理存放：
  logical index只通过activation-local cursor映射到physical index；不得新增
  permutation table或ciphertext padding/副本。Generated-C gate必须以
  `AFFINE_CIPHERTEXT_STORAGE`验证该结构，并以
  `INVALID_AFFINE_CIPHERTEXT_STORAGE`阻断identity/direct-index回归；空/单字节
  identity是不可避免的窄例外。
- Sensitive generated-C text只在真实use-site首次到达时解码；同一C function内的同
  明文共享一个activation-local slot并在该activation内最多解码一次，不得跨function
  共享slot、plaintext cache或encoding identity。函数内slot使用聚合scratch与统一
  cleanup hook覆盖normal/early/failure exit；能明确更短use window的owner/table
  metadata继续优先显式decode/use/zero。只有低敏感普通runtime error文本可显式选择
  lazy-once；generic/global decoder、集中text-pointer目录和单decoder批量覆盖必须被
  source audit阻断。允许translation-unit内共享一个metadata-free `noinline`
  zeroizer/cleanup callback；它只能接收scratch地址/长度，不能接收或解析
  ciphertext、codec、owner/member/descriptor、token，也不能成为shared decoder或
  plaintext cache。
- 固定异常类型/错误文案只有进入显式closed allowlist后，才可outline到
  build-scoped hash-only `noinline,cold` leaf。该leaf只能接收`JNIEnv*`，不得接收
  owner/member/descriptor、业务字符串、metadata token或Java value；相同低敏感文本
  可在leaf fragment内复用一个lazy-once encoding，各function只触发自身实际引用的
  decoder。高敏感文本和未列入allowlist的错误仍保持activation-local lifetime。
- Registration rollback/exception-restore diagnostics必须使用registration text domain，
  只在对应`FatalError`路径解码；generated-C gate以
  `STABLE_REGISTRATION_DIAGNOSTIC`阻断稳定明文xref锚点与任意direct/adjacent
  `FatalError` C string literal。Emitted LLVM中的
  string-token SSA value name也必须是build-scoped hash-only identifier。
- Registration method name/descriptor只允许在同一owner、同一purpose domain内复用
  decoded scratch；禁止跨owner复用。owner不超过64个bindings且去重后的text scratch
  不超过16KiB时使用有界栈storage，任一上限超出时使用heap；两条路径都必须清零
  text scratch与`JNINativeMethod[]`，heap路径随后释放。
- `LLVM_NATIVE_PATH` JNI wrapper 与规范 LLVM body 之间使用 build-scoped local ABI topology。final native ABI传递`JNIEnv*`或owner `jclass`的JVM/JNI semantic-surface binding强制使用bounded branched参数重排；pure-native scalar binding仍从direct canonical、单层、双层与branched四种形态中派生，以保留较低成本的build diversity。branched形态只在wrapper activation内从两条最多双层的local route中选择，并用最多三个`static __attribute__((noinline, used))` bridge控制代码膨胀；不得使用`optnone`阻止正常size optimization。只允许重排真实原生参数，不得添加cookie、持久function-pointer data slot，bridge不得执行JNI、改变reference lifetime或观察/清除pending exception。该变换只提高静态分类成本，不是安全边界。
- 静态分析难度优先于产物大小，但每个加固必须有明确size budget：优先选择
  table-free、bounded topology和同值组内复用；攻击者回归记录final native与
  generated-C字节数及dual-build delta。size evidence当前用于回归和取舍，
  不能替代语义、plaintext、export或final-binary审计；只有对应真实六目标、
  dual-build与Ghidra证据已在hardening文档中记录时，才能宣称相应验收范围。
- IR call-indirection group必须同时绑定 Java/SSA signature与final native hidden ABI（`JNIEnv*`/owner `jclass`）；planner按两者分组，validator/backend都拒绝mixed function-pointer type，不能为提高覆盖而放宽。
- Hidden/internal linkage和 final dynamic export allowlist audit不可关闭。只导出 `JNI_OnLoad`/registration所需 C ABI roots。

## Zig 与 Cross-Target Build

- Schema v1固定 managed Zig `0.15.2`，位置为可执行 `j2ll.jar`同级 `zig/zig(.exe)`。
- 缺失/版本不对时先复用同目录 official archive，否则从 Zig 0.15.2 official URL下载；local/downloaded archive必须先按内置官方 SHA-256验证再解压。Signature状态明确为 `notVerifiedBoundary`。
- 一个 generated `build.zig`和一次 matrix-wide invocation编排 per-class `.ll`、Zig-managed `.o`、JNI wrapper C和 runtime helper C。Source set不得含 selected method bytecode carrier。
- Generated C compile unit使用`ReleaseSmall`；per-class LLVM input与final link module保持`ReleaseSafe`。Observable compile unit必须按source kind同质分组，不能把C与LLVM混入同一unit后共用错误的optimization mode；总数仍以每target最多64个为界。
- Production Zig source generation从final validated LLVM module model的真实symbol
  references计算runtime-helper family reachability，只发出闭包所需family；仅有
  declaration不能成为reachability root。classifier只接受精确已知stable symbol或
  有严格declaration evidence的build-local symbol；任何未知`j2ll_rt_*`/`j2ll_h_*`
  引用或不完整model evidence都fail closed到保守全量runtime source。选中binding-
  driven family后还必须对该emitter实际会写出的stale binding entries补齐跨family
  closure；直接generator/兼容API默认同样使用保守全量计划。
- 固定六目标：Windows GNU x64/arm64、Linux GNU x64/arm64、macOS x64/arm64。Selected targets默认 required；真实 capability/preflight/compile/link failure用 `ZIG_TARGET_UNBUILDABLE`阻止 final JAR。Cross-link evidence不等于 non-host OS/JVM runtime E2E。
- Final workspace libraries扁平写入 `native/<library-file-name>`，Zig workspace为 `native/zig-workspace/`；JAR path为 `<embeddedLibraryDirectory>/<library-file-name>`。
- `native/zig-cache/**`是非权威的Zig duplicate cache，不进入plaintext hit枚举；flat final library与`native/zig-workspace/**` generated source仍必须逐target审计，不能借cache exclusion放宽。
- 不新增 host `cc`/`clang`/`llc`/platform linker旁路。
- Export/content retention roots必须真实引用所需 LLVM/helper members；不能把普通 archive link误作 whole-archive。

## CLI、Progress 与 Output

- CLI固定为 `j2ll [--config <Config.json>] [--validate|--dry-run] [--debug]`，另有 `--help`/`--version`。无 mode默认 build。
- Validate只校验 config且不建 workspace；dry-run不调用 Zig、不写 final JAR。Dry-run/build在 resolved `outputDirectory`下创建 `build_yyyy-MM-dd_HH-mm-ss[-n]`，final JAR在 workspace根。
- `--debug`只开启 intermediates/debug dumps，不宣称 native debug symbols。
- stdout只写稳定 `key=value`结果；progress、skipped notice/prompt和 diagnostics写 stderr。
- TUI compiler stages为 `Read bytecode`、`Lower to IR`、`Emit LLVM IR`；native期间一个 aggregate row加每 target一行；完成后折叠 target rows；finalization用 `Finalize JAR`。
- Target百分比只等于真实 Zig build-graph completed work units/total，最多64个按source kind同质的observable compile units/target。`BUILDING`/`LINKING`/`COMPLETED`来自 graph boundary，不解析 Zig文本或按耗时猜测。
- `logs/zig-progress/`仅 invocation期间存在，成功/失败/中断都删除；持久 `zig-build.log`只用于诊断。
- CLI/config error、exit code、summary/index/failure report合同按 `docs/io-config-output-contract.md`。

## Reports 与 Audit

- Primary reports稳定排序并写 `schemaVersion`/`reportVersion`；config/protection只写 seed hash。
- `lowering-report.json` 的 helper evidence 只写 non-sensitive kind 与 domain-separated identity hash；不得序列化含 owner/member descriptor或business string carrier的完整helper字符串。
- 必需 evidence包括 diagnostics、lowering、skipped-method、field-internalization、packaging、protection、symbol-audit、artifact-audit、support/opcode matrix、known blockers、summary/index/readiness。
- Artifact audit验证：
  - 每个 `nativeLowered` method有native implementation与selected-target artifact闭包；Java入口保留者有wrapper/registration，`internalNativeOnly`则有完整native-caller closure且无Java declaration/registration；
  - 每个 `skipped` method原 body保留、无 registration/native bytecode copy；
  - 唯一 Loader API/version/name正确；
  - native resource SHA、JAR metadata、report manifest、export allowlist、PDB与 sensitive plaintext policy一致。
  - plaintext canonical surface覆盖generated C/LLVM、flat final native library与primary report；最终库命中即使source干净也必须阻断。
- Audit/readiness/signing/required-target failure不得保留成功态 final JAR；failure report写 `finalArtifactWritten=false`。
- Release suite覆盖 minimal LLVM、mixed helper/protection、精确 skipped boundary、confirmation Y/N/EOF、config/signing/target/audit expected failure、packaging preservation、determinism与 realistic samples。
- 长期路线按 stable skipped reason逐项补 frontend/SSA/backend/helper/runtime E2E，持续减少 skipped methods。

## 测试要求

- 新 opcode：frontend/SSA + validator/backend；涉及 runtime语义再补 child-JVM parity。
- 新 helper：catalog/declaration/C source ABI + pending-exception/reference lifetime + E2E。
- 新 analysis/pass：focused unit、deterministic seed/no-op、validator与 pipeline integration。
- 改 method outcome/confirmation：Y/N/EOF、piped stdin、non-TTY、no-skipped no-read、dry-run/validate no-read、Zig-not-invoked-on-decline。
- 改 packaging/Zig：preservation、registration、artifact audit、target/export/content tests；必要时 real-Zig host与六目标 structural evidence。
- 测试强度匹配风险；文档-only change可不跑完整 suite，但交付前至少做 targeted grep/consistency review。

## Git、健壮性与文档

- 在阶段完成、交付、提交或怀疑并发改动时检查 `git status`/`git diff`；不还原用户或其他 agent改动。
- 不使用破坏性 git命令，除非用户明确要求。
- 正确性优先；不确定时跳过整个 selected method并给稳定 reason，不生成可能错误的 native code。
- 每个 stage有 validator/diagnostic/focused tests；不让 backend修前端非法状态。
- 同一输入尽量保持 diagnostics、IR、symbols、reports稳定排序。
- 新 stage、helper policy、skipped boundary、validator、测试落点或目录边界必须同步更新本文件与对应 docs；README保持用户视角。
