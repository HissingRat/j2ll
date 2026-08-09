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
- `nativeLowered`：原业务语义由经过验证的 LLVM、生成式 template/stub或 JNI/runtime helper-backed native implementation完成。Java入口保留时原 Code按合法策略替换且native body/registration都有证据；经method-internalization批准的internal-only method则必须有native caller closure和无Java declaration/registration的证据。其物理retention可以是独立hidden LLVM body，也可以在严格single-call-site coalescing后只存在于caller body中。
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
- 可变候选仅限 input-base `private static` primitive/reference/array field；instance field、volatile/final、field-owning `<clinit>` access、multi-release与 reflection/serialization/JNI/Unsafe/VarHandle/MethodHandles Lookup/agent observation边界保留 JVM field。
- 另支持 `private static final` classfile `ConstantValue`：`Z/B/S/C/I/J/F/D`的显式same-owner LLVM-native `GETSTATIC`在普通IR protection前精确折叠为SSA常量，使新常量仍进入constant-protection/coverage；float/double必须按raw bits发出integer constant + bitcast以保留NaN payload与negative zero。已经没有任何field reference的primitive/String declaration可直接删除。该路径没有native slot、sidecar或运行时storage。显式String `GETSTATIC`必须保留字段，直到存在能证明保持JVM intern/object-identity语义的helper；任意write、cross-owner、non-LLVM accessor或dynamic observer同样fail closed。字段观察按exact field、known owner、unknown global三级收敛；覆盖reflection/Field、MethodHandle/VarHandle、Unsafe、JNI/agent、ConstantDynamic字段bootstrap与bootstrap arguments。所有非exact closed-JDK allowlist的custom bootstrap target和运行时`defineClass`/hidden-class/Unsafe define入口必须global fail closed；不能只按observer caller owner判断字段可见性。
- Dynamic-observer dataflow只对含observer call的method建立frame；producer lattice最多保留8个source，超限使用per-slot canonical unknown。每次provenance query另有4096-step/128-depth共享预算，包括Handle→VarHandle和Unsafe→Field嵌套路径；超限必须global/null fail closed，不得让ASM `SourceInterpreter` producer set或resolver DAG无界增长。
- 每个真实 accessor必须是same-owner static或instance method，最终为`nativeLowered`且 final implementation path为支持对应storage ABI的`LLVM_NATIVE_PATH`。任一cross-owner、unselected、`skipped`或non-LLVM accessor都保留JVM field；不存在bytecode-accessor rewrite path。
- Primitive使用 per-defining-`jclass` weak-keyed relaxed atomic raw bits，按 descriptor执行 boolean low-bit、窄整数截断/扩展和 float/double bitcast。
- Reference/array始终留在 JVM heap，由唯一 Loader按需加入 `ClassValue<Object[]>` sidecar强持有。`ClassValue`是跨调用 cache；native activation首次实际访问时惰性获取 local ref、复用并在退出时释放，不建立 native strong global ref。
- Native instance wrapper向field sidecar传递method/field的declared defining `jclass`，不得用`GetObjectClass(self)`按receiver runtime subclass分裂static storage。
- Final plan、IR slot/constant rewrite、FieldNode removal和 residual declaration/instruction/Handle/bootstrap audit共用同一 approved plan并 fail closed；field final validator必须在native-only coalescing更新最终implementation plan之后执行。

## Method Internalization

- `methodInternalization`是直接boolean，默认`false`；declared `CLOSED_WORLD`可分析private/protected和exact allowlisted public候选，本次build明确Y授权的current-input-JAR-only scope可分析private/protected及exact allowlisted public static。授权是feature-scoped，不改写`worldModel`；current-JAR-only不读取configured `classPath`，并把JAR外caller/subclass/reflection/JNI/agent observer明确记为用户接受的范围外风险。
- `publicMethodInternalizationAllowList`是required `array<string>`、示例默认`[]`；每项必须是无wildcard、无重复的exact `<owner>#<name>!<descriptor>`。public static可使用declared `CLOSED_WORLD`或本次Y授权；public instance只允许configured world为declared `CLOSED_WORLD`且input+全部configured classPath形成parse-complete hierarchy/call world时分析。combined world缺失任一superclass/interface时，该public instance候选以`METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE`保留；不得因此整批禁用public static/private/protected候选。
- V1候选仅限已有final `LLVM_NATIVE_PATH`的ordinary Code-bearing method：支持private/protected static（static caller可cross-owner）和same-owner exact private/protected instance；exact allowlist另可授权public static及same-owner exact public instance。public instance不要求method/class为final，也不因存在可覆写slot本身拒绝，但scope内每个调用点都必须exact解析到候选且caller仍须same-owner；实际导致non-exact dispatch的override会拒绝该候选。已解析的exact reflection/MethodHandle/Handle/bootstrap/ConstantDynamic/EnclosingMethod observer、launcher/agent entry，以及closed exact catalog识别到的JVM/JDK callback（包括Object虚方法、Runnable/Callable、线程/定时器、序列化和常见`java.util.function`合同）一律保留Java method；catalog只按真实hierarchy subtype/implements关系与exact descriptor匹配，不恢复blanket override-slot veto。catalog外第三方framework callback与无法穷举的reflection/JNI/agent动态观察面仍是allowlist加world授权显式接受并进入warning/report的风险。cross-owner instance、interface、constructor、class initializer、synchronized、bridge/synthetic、multi-release、零native caller或任一非LLVM caller同样保留。
- Approved method仍是`nativeLowered`，rewrite strategy为`internalNativeOnly`；output class删除整个method_info，且不进入`RegisterNatives`。默认retention为独立hidden LLVM body；若随后满足唯一直接call site、纯标量/non-throwing、无field/call/monitor/JNI-owned-reference、非递归和validated inline边界，则按bottom-up顺序自动合并进唯一caller，retention报告为`coalescedNativeOnly`并完全不发出callee的LLVM function/declaration/reference或generated-C wrapper。coalescing使用独立的96-instruction callee budget和每caller最多64个site；A→B→C这类chain必须逐轮重定向所有已合并成员的physical owner，直到稳定或达到internalized-method数上限。initializer-plan caller当前显式保留standalone body，直到initializer plan与rewritten caller能作为一个atomic artifact重建；任一证明失败都保留独立hidden body。inline rewrite必须保留caller中所有未被替换call的call-indirection metadata。所有ordinary Java/Handle/bootstrap/EnclosingMethod residual必须在final JAR audit中为零。
- same-owner direct scalar call继续使用validated LLVM direct ABI。cross-owner static和exact same-owner instance dispatch使用binding-local internal bridge；bridge不得执行`GetMethodID`/`Call*MethodA`，而是进入hash-only internal wrapper。reference/owned/pending-exception target必须使用nested JNI local frame：`PushLocalFrame`，descriptor-aware参数解包，normal reference result经`PopLocalFrame(result)`提升；pending exception需清除、跨frame提升并严格恢复，失败`FatalError`。
- Analysis、final-plan validator、registration filter、generated-C bridge、MethodNode removal、lowering/packaging report与artifact residual audit必须消费同一immutable approved plan并fail closed。Coalescing另有immutable physical-retention plan；LLVM compiler、host-C binding filter、lowering report与workspace/source audit必须共同证明callee standalone surface为零且caller仍被编译。

## Runtime Loader 与 Packaging

- 唯一 runtime class是 Java 17 `<embeddedLibraryDirectory>/Loader.class`。
- Loader只包含 target选择、SHA-256校验、extract/load/register，以及 field plan需要时的 `ClassValue<Object[]>` sidecar；不得包含 hidden/generated class definition或 embedded-bytecode decode API。
- 不输出旧 runtime support class、companion/nested Loader或 artifact-specific NativeLoader。
- `embeddedLibraryDirectory`同时是 resource与 Loader package prefix，必须为规范 Java internal package path；input base/MR同名 Loader在 Zig前分别以稳定 collision reason失败。
- 普通 Code method可用 `nativeOriginal`；`<init>`、`<clinit>`和有 Code interface method使用合法 stub + generated native body helper。无法把全部用户语义移入 native implementation时整个 method为 `skipped`。
- Packaging只重写 `nativeLowered` methods：registered入口按既有native/stub策略重写，`internalNativeOnly`精确删除method_info；`skipped` methods精确保留并验证没有registration/native bytecode copy。
- `JNI_OnLoad`只允许对唯一`<embeddedLibraryDirectory>/Loader` anchor调用JNI `FindClass`，再从该`jclass`取得exact defining `ClassLoader`。所有business registration owner必须把activation-local slash name原位转为binary name，并通过`Class.forName(name, false, definingLoader)`非初始化解析；不得对business owner调用`FindClass`，也不得回退TCCL/system loader。anchor、Class meta-object与defining-loader context只保留为本次`JNI_OnLoad` local refs，不建立global/weak-global class cache；owner scratch在lookup窗口立即清零。多 owner registration必须原子；单个 owner 的 `RegisterNatives` 失败也必须先对当前 owner执行严格回滚，再把原异常交给外层逆序`UnregisterNatives`此前成功owner并清理local refs/scratch。只有每次unregister都返回`JNI_OK`且无pending exception、恢复原异常的`Throw`返回`JNI_OK`并形成pending exception时才允许普通失败，否则`FatalError` fail closed。
- Loader load state必须是per-defining-ClassLoader的fail-closed `UNLOADED -> LOADING -> READY` / `FAILED`状态机。`LOADING`在`System.load`前写入，`READY`只能在`System.load`及`JNI_OnLoad`完整返回后写入；同线程重入看到`LOADING`必须在第二次load前抛出稳定`UnsatisfiedLinkError`，任何Throwable使状态进入`FAILED`并原样向首次调用方传播，后续调用不得重试或假装ready。
- 保留 manifest/resources/services/module-info/multi-release entries。Base class有 versioned counterpart时，命中 method为 `skipped` + `MULTI_RELEASE_VERSIONED_CLASS`，不 rewrite/register。
- Signed JAR：`fail`在 rewrite前拒绝；`strip`移除 signature entries并 warning；`resign`先 preflight keystore/password/alias，再用当前 JDK `jarsigner`。失败不保留 final JAR。

## JVM 语义边界

- SSA merge使用 block parameters，terminator携带 target arguments；live-in mismatch或未建模 throw-site local frame把整个 method标记 `skipped`。
- Typed catch、exception edge、显式 `athrow`和 implicit exception sites必须显式建模。Unprotected JNI pending exception立即走descriptor-safe return并保留pending state；protected site先清除pending state，再按classfile顺序dispatch typed/catch-all handler。缺少pending/handler-transfer evidence、复杂 finally/state merge/monitor interaction未支持时跳过整个 method，不能继续执行 pending exception。
- Method inlining 只能删除 direct-call 的无 handler synthetic pending-exception evidence：callee 必须已证明为 pure/non-throwing，site 没有 handler，exception value 没有任何 use。protected edge、specific exception kind或observable exception value必须保留并跳过该 inline candidate。
- Monitor/synchronized/volatile/final/thread happens-before使用 JVM/JNI helper/marker；`Thread.sleep(J)V`通过JVM-backed helper执行并保留`InterruptedException`语义。不伪造 scheduler或 monitor queue，未支持的其他Thread/wait-notify caller为 `skipped`。
- Class init active-use guard与 `<clinit>` begin/end/failed helper必须保持 JVM ordering。普通optimization/intrinsic后可把exact same-block `CONST_LONG -> CLASS_OBJECT -> CLASS_INIT_GUARD -> CLASS_INIT_HAPPENS_BEFORE -> active operation` carrier融合进same-owner `GET_STATIC`/`PUT_STATIC`或仍走JVM dispatch的`CALL_STATIC`；operation必须完整保留，只有normal continuation随后发出acquire。`NEW_OBJECT`、可能direct-native的static call、额外carrier use、owner或exception-frame不一致一律不融合；任何后续field/call rewrite仍须证明active-use语义不丢失。
- JDK/reflection/MethodHandle/lambda/Unsafe/VarHandle只有 validated direct/helper/dispatch matrix算 `nativeLowered`；超出 matrix的 selected caller为 `skipped`。
- Exact `ByteBuffer.allocate(4).putInt(i).array()`且allocate/putInt中间值same-block、unique-use、不逃逸时，在普通optimization后、protection前改写为三个JNI-native frame helper。它必须保持原三个call-site exception boundary和求值顺序，通过`NewByteArray`创建真实JVM `byte[]`，只用4-byte native stack scratch写big-endian内容；其他capacity、escaping/aliased、跨block或indirected shape保持普通JVM dispatch。
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
- `ControlFlowFlatteningPass` 使用 bounded single-entry/multi-exit region plan：每个method
  最多选择4个互不重叠region，每个region包含2到32个原始member block。每个region拥有
  独立dispatcher，state使用per-build/per-method/per-region派生的dense permutation，
  状态集合始终为`[0, regionMemberCount)`；不得扩大状态空间、引入permutation table或
  增加每次transition的查表工作。
- CFF dispatcher会为自己的member blocks引入synthetic cycle。产生owned JNI local ref、
  instruction exception site、exception edge/handler、monitor/JMM marker或class-init敏感
  操作的block不得进入region；block parameter、edge target argument和跨block
  instruction-defined SSA value不能由当前dispatcher ABI携带时同样留在region外。
  region外block及其exception/ownership语义保持原样，region exit直接进入原region外
  target，不能经dispatcher延长local-ref lifetime。只有至少一个region真实改写且通过
  validator时该method的CFF coverage才写`affected=true`；没有safe region只产生
  pass-level `SKIPPED`，不得改变method outcome或绕过ownership/release proof。
- Final `LLVM_NATIVE_PATH`与 compiler-internal helper只由 `NativeLlvmCompiler`编译一次；reports、intermediates和 Zig writer共用同一 validated module/pass result。
- Protection pass对单 method不适用只记录 pass `SKIPPED` reason，不自动改变 method outcome；compiler/runtime implementation无法保持语义才产生 method `skipped`。
- Protection coverage必须由producer逐method或真实module subject显式写`requested/applicability/affected/status/reasonCode`；function pass只按`affectedFunctions`映射，module/global pass不得把一个global变化扩写成所有method affected，validation failure无法确定逐method applicability时写`unknown`。collector不得从汇总`SKIPPED`或旧`affectedMethods`推断。
- Generated identifiers使用 hash-only token；JNI必要 owner/member/descriptor/error metadata在 generated C中 encoded at rest。Report不写 raw seed或 plaintext。
- `StringEncryptionPass`的新产出固定为`enc:v2`：carrier数值token必须与
  encrypted-payload key绑定，token SSA名称与数值都由build/method/site材料派生；
  `enc:v1`只允许作为compiler-internal兼容读取边界，不能重新成为生产emission。
- 通用 runtime metadata、business string与registration text分别消费独立
  build material。Native text按build/purpose/use派生compact 32-bit site-bound
  codec family与schedule并内联到owning activation；不得恢复统一decoder、固定
  全局codec shape或相邻XOR seed-share/cipher。Generated-C gate
  必须阻断decoder fanout、fixed-shape和adjacent-seed回归。
- 多字节native-text ciphertext按build/purpose/use派生的affine bijection物理存放：
  logical index只通过activation-local cursor映射到physical index；不得新增
  permutation table或ciphertext padding/副本。Generated-C gate必须以
  `AFFINE_CIPHERTEXT_STORAGE`验证该结构，并以
  `INVALID_AFFINE_CIPHERTEXT_STORAGE`阻断identity/direct-index回归；空/单字节
  identity是不可避免的窄例外。
- Sensitive generated-C text只在真实use-site首次到达时解码。同一C function内同明文
  可共享一个singleton slot；distinct literal只有在lexer证明它们是同一个direct C call
  argument list、因此必然共同求值时才可合并为tuple。每个tuple最多8个component且最多
  512 decoded bytes，单个超长component只能独占一组；不同call、assignment或分支不得
  互相提前解码。tuple使用一个affine ciphertext，但每个component必须先使用独立的
  build/purpose/use-derived lane mask参与真实cipher bytes，不能只影响排序/identity。
  component offset只作为compile-time literal进入use-site，不生成pointer/offset table；
  tuple在该activation内最多解码一次。single-use和同一direct-call argument list的tuple
  使用guardless单decode fast path；同一call里的其他argument只计算scratch+常量offset，
  不读取未初始化明文，跨call/assignment复用才允许`ready` guard。不得跨function共享tuple、slot、plaintext cache或
  encoding identity，source audit必须以`CROSS_FUNCTION_NATIVE_TEXT_TUPLE_REUSE`阻断
  回归。函数内slot使用聚合scratch与统一cleanup hook覆盖normal/early/failure exit；能
  明确更短use window的owner/table metadata继续优先显式decode/use/zero。只有低敏感普通
  runtime error文本可显式选择lazy-once；generic/global decoder、集中text-pointer目录和
  单decoder批量覆盖必须被source audit阻断。允许translation-unit内共享一个
  metadata-free `noinline`
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
- Registration method name/descriptor只允许在同一owner、同一purpose domain内复用。
  去重后的文本按最多8项且最多512 decoded bytes组成bounded group，每group只保留一个
  encoding/decoder；禁止跨owner或跨purpose复用。单个超512-byte文本允许独占一组。
  owner不超过64个bindings且全部decoded text scratch不超过16KiB时使用有界栈storage，
  任一上限超出时使用heap；两条路径都必须清零text scratch与`JNINativeMethod[]`，heap
  路径随后释放。固定registration rollback/exception-restore文案仅通过四个build-local
  hash-only `noinline,cold` leaf发出，leaf只接收`JNIEnv*`。`JNI_OnLoad`使用activation-
  local `jclass[]`与`registered_count`逆序cleanup/rollback，不展开per-owner重复控制流。
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
- `protection.binary.retainUnwindInfo=false`对Linux/macOS generated-C加no-unwind
  flags，并只在final canonical LLVM module model的function/instruction native-unwind
  evidence全部为`PROVEN_ABSENT`时，为该module生成target-selectable的`nounwind`文本
  变体。`REQUIRED`、`UNKNOWN`、证明不完整或未建模`.o`输入一律保留；Windows因SEH、
  `--debug`或config requested retain也始终选择retained变体。canonical model只有一份，
  proof绑定该model，dual emission不得用`.ll` regex；不能假设Zig module unwind flag或C
  compile flag会改写经`addObjectFile`输入的`.ll`。manifest按target写generated-C
  decision、LLVM omitted/retained counts、unmodeled object count、final omission
  expectation与reason；若Linux/macOS预期完全省略，final ELF的`.eh_frame`/
  `.eh_frame_hdr`或Mach-O的`__eh_frame`/`__unwind_info`仍非空必须阻断构建。
  Linux/macOS generated-C另启用最低收益阈值为16的bounded machine outliner，避免为
  少量字节收益共享native-text短片段；Windows因SEH directive边界禁用。
- Final generated-C surface经`NativeLibcRequirementPlan`闭集检查；无libc调用时使用freestanding/minimal compile-only headers、禁止implicit call和shared-library undefined、`.link_libc=false`。Windows使用minimal DLL entry，Linux/Windows最终库不得有libc/CRT dependency。macOS无业务libc调用时仍有平台强制`libSystem`，manifest必须以`MACOS_PLATFORM_LIBSYSTEM_REQUIRED`区分source requirement与effective dependency；新增/漏扫外部routine必须fail closed。
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
- TUI compiler stages为 `Read bytecode`、`Lower to IR`、`Emit LLVM IR`；target构建前固定显示`Generate C`、`Audit native`、`Write LLVM`、`Prepare Zig`四条真实completed/total准备进度，managed Zig定位只占用瞬态`Stage`行、不伪造进度条；native期间保留准备完成行并显示一个aggregate row加每target一行，完成后只折叠target rows；finalization用`Finalize JAR`。
- Target百分比只等于真实 Zig build-graph completed work units/total，最多64个按source kind同质的observable compile units/target。`BUILDING`/`LINKING`/`COMPLETED`来自 graph boundary，不解析 Zig文本或按耗时猜测。
- `logs/zig-progress/`仅 invocation期间存在，成功/失败/中断都删除；持久 `zig-build.log`只用于诊断。
- CLI/config error、exit code、summary/index/failure report合同按 `docs/io-config-output-contract.md`。

## Reports 与 Audit

- Primary reports稳定排序并写 `schemaVersion`/`reportVersion`；config/protection只写 seed hash。
- `lowering-report.json` 的 helper evidence 只写 non-sensitive kind 与 domain-separated identity hash；不得序列化含 owner/member descriptor或business string carrier的完整helper字符串。
- 必需 evidence包括 diagnostics、lowering、skipped-method、field-internalization、packaging、protection、symbol-audit、artifact-audit、support/opcode matrix、known blockers、summary/index/readiness。
- Artifact audit验证：
  - 每个 `nativeLowered` method有native implementation与selected-target artifact闭包；Java入口保留者有wrapper/registration，`internalNativeOnly`则有完整native-caller closure且无Java declaration/registration；`coalescedNativeOnly`还必须无callee LLVM function/declaration/reference、generated-C wrapper或workspace source symbol residual；
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
