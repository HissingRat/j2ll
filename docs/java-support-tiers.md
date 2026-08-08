# Java Support Tiers

本文档定义 rewrite 后 j2ll 对 Java/JVM 特性的支持等级。所有 tier 都以 Java 17 或更新版本上的 JVM-hosted 输出 JAR 为前提：GC、class loading、thread scheduling、monitor、object identity 和 Java object lifetime 均由 JVM 负责。唯一 `<embeddedLibraryDirectory>/Loader.class` 固定为 Java 17 classfile。

Tier 是 compiler-development 与 release-evidence 分类，不是用户 Config 选项。selector 命中的 Code-bearing method最终只有：

- `nativeLowered`：由 LLVM、生成式 template/stub 或经过验证的 JNI/runtime helper-backed native implementation完成。普通Java入口保留native declaration/stub并注册；whole-program method-internalization批准项保留native caller closure并删除Java method_info与registration，其物理实现可以是独立hidden body或严格合并进唯一caller的`coalescedNativeOnly`。
- `skipped`：保留原 Java method/classfile 形态，不生成 native body，不进入 `RegisterNatives`。

`excluded` 只描述 selector 外方法；pipeline/toolchain/packaging failure 是 build status。Schema v1 不增加 `requiredNative`，也不在 native artifact 中保存 selected method 的 bytecode副本。

## Tier 0: Classfile / JVM Core 基座

目标：可靠读懂 `.class`，建立后续所有阶段的事实模型。

功能范围：

- classfile parse：class、field、method、descriptor、access flag、constant pool。
- method bytecode CFG：branch、switch、return、throw edge、exception handler edge。
- StackMapTable/frame facts。
- JVM type model：primitive、reference、array、void、category-1/category-2。
- verifier-like checks 与 deterministic diagnostics/dumps。

测试要求：

- ASM 构造最小 class/method fixture。
- descriptor/signature parse、CFG golden、malformed input diagnostic。
- deterministic output。

Tier 0 尚未承诺 executable native lowering，但 selector audit仍不能丢掉命中的 declaration。

## Tier 1: 基础 Java 语言子集

目标：普通 Java 方法能从 bytecode lower 到 SSA，再到 LLVM或生成式 native helper。

功能范围：

- `boolean/byte/short/char/int/long/float/double` arithmetic、compare、conversion。
- local、field、static field。
- JVM-managed object/array allocation、constructor、`this`。
- static/special/virtual call。
- `<init>` 保留精确的 verifier-required Java prefix：从入口到真正初始化 `uninitializedThis` 的 `this(...)` / `super(...)` 调用（含原 descriptor 与实参计算），初始化后的 body 交给 same-owner private static native helper。
- `<clinit>` 保留 loader/bootstrap Java stub；当完整 initializer IR 可由最终 native plan表达时，原 body 交给 same-owner private static native helper。
- null check、cast、`instanceof`、String constant/concat。

测试要求：

- primitive arithmetic/conversion parity。
- object/field/array、null/cast/bounds、constructor/class-init ordering。
- original JVM 与 output JAR child-JVM differential。

有 Code 的 method 只有在全部用户语义都由 native implementation承担时才是 `nativeLowered`；否则整个 method 为 `skipped`。

`methodInternalization`不会新增第三种outcome。它只在final `LLVM_NATIVE_PATH`已经证明后，删除只由最终LLVM caller可达的private/protected static或same-owner exact instance入口。public必须额外命中required exact `publicMethodInternalizationAllowList`：public static可使用declared `CLOSED_WORLD`或本次Y授权的current-JAR-only scope，public instance只允许declared `CLOSED_WORLD`并合并input与完整classPath world。public instance不要求method/class为final，也不因可覆写slot本身拒绝，但每个调用点必须exact且caller仍须same-owner；已解析的exact reflection/MethodHandle/Handle/bootstrap/ConstantDynamic/EnclosingMethod observer、launcher/agent entry与closed exact catalog识别到的Object/Runnable/Callable/线程/定时器/序列化/常见函数式JDK callback都会保留Java入口。callback catalog要求真实hierarchy关系与exact descriptor，不是blanket override-slot veto；catalog外第三方framework callback及无法穷举的reflection/JNI/agent动态观察面仍作为用户接受风险进入warning/report。reference-returning internal call仍通过JNI nested-local-frame bridge维持GC/local-reference/pending-exception语义；任何unselected/skipped caller或non-exact virtual dispatch同样fail closed。一个独立的physical-retention优化只会把唯一直接call site、pure scalar/non-throwing、无field/call/monitor/JNI-owned-reference的bounded callee合并进caller；其他internalized method仍保留hidden body。

## Tier 2: 常见 Java 语言特性

目标：用户写的普通现代 Java 代码大部分能进入 native pipeline。

功能范围：

- generics erasure、bridge method 与 `Signature` metadata保留。
- LambdaMetafactory 常见 capture/non-capture shape。
- string-concat 与常见 invokedynamic bootstrap。
- 有 Code 的 default/static/private interface method使用合法 stub + generated native helper。
- enum、annotation metadata、record/sealed metadata与普通方法。

Bridge、synthetic、enum-generated 和 record-generated methods默认按普通有 Code method处理；flags只进入 audit/report，不导致 skip。selector 命中的 abstract、already-native、无 Code interface declaration或 annotation element只记录 eligibility evidence，无 method status且不触发 confirmation。

测试要求：

- bridge/default/interface conflict/override。
- lambda capture/non-capture、enum、annotation/record metadata。
- invokedynamic bootstrap allowlist与 unsupported caller preservation。

## Tier 3: JVM 语义完整性层

目标：exception、monitor、thread、JMM 和 GC语义不能静默出错。

功能范围：

- try/catch/finally、multi-catch、rethrow与受限 cleanup shape。
- `monitorenter`/`monitorexit`、`ACC_SYNCHRONIZED` 和异常退出释放。
- JVM-hosted Thread互操作，不实现 native scheduler。
- volatile、final publication、monitor happens-before的保守 marker/helper。
- JNI local/global reference lifetime。
- Ownership-aware local-reference release使用site-sensitive liveness区分normal live-out与instruction exceptional needs，覆盖可证明的普通路径、parallel edge adapter、loop/backedge重定义、typed/catch-all handler transfer/exit与显式`athrow`，并在last use或唯一ownership transfer边界承担`DeleteLocalRef`责任。重复transfer、handler live-set不一致或其他无法证明有界释放的shape以`UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME`整方法跳过。返回reference或内部产生owned/pending-exception reference的registered native callee通过JVM/JNI bridge获得嵌套local frame；direct LLVM call只保留给不产生这些reference的callee，无法桥接的compiler-internal shape fail closed。
- 激进优化未来需要的 guard/slow-path；slow path必须是显式 JVM/JNI helper语义，不能重放 selected caller bytecode。

当前边界：

- 可抛出 JVM exception 的 JNI/runtime helper instruction位于 user try region时，SSA 显式携带 pending exception value、按 classfile 顺序排列的 handler edge，以及 throw-site live locals；throwable和locals通过block arguments进入handler parameter。
- LLVM在每个受保护 helper site后立即读取pending exception。存在异常时先清除JNI pending state，再按声明顺序执行typed `instanceof`匹配；catch-all直接进入handler，全部typed handler不匹配时恢复并rethrow原异常。显式 `athrow` 使用同一有序handler dispatch，但不重复读取/清除pending state。
- simple typed/multi-catch、catch-all continuation以及受限cleanup/rethrow shape已进入真实 native path。无法形成一致throw-site frame/block arguments、不可约exception-state merge、复杂monitor/finally interaction，或当前包含任意exception table的constructor仍将整个 selected method标记为 `skipped`。
- CFF只把不产生owned JNI local ref、且不含exception/handler/monitor/JMM/class-init
  边界的safe blocks放入bounded single-entry region；同一method中的owned producer可留在
  region外并继续使用既有release proof。若没有至少2个block的safe region，该pass以稳定
  `CONTROL_FLOW_FLATTENING_*`原因记录`SKIPPED`并保留输入IR；无论CFF是否适用，method仍可
  沿已验证的ownership-aware native path成为`nativeLowered`。
- synchronized method/block已有 JNI monitor E2E；`Thread.sleep(J)V` 通过 JVM-backed helper执行并保留 `InterruptedException` pending-flow。`Thread.start/join`、Thread constructor与wait/notify等尚未接入真实 helper matrix的shape仍统一 `skipped`，不伪造 native scheduler或 monitor queue。

测试要求：

- exception/finally/synchronized runtime parity。
- owned local-ref的loop/backedge、handler与direct-call release parity，以及无法证明有界时的精确`UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME`负例。
- volatile/multithread smoke。
- skipped method原 body保留、无 registration/native bytecode copy。

当前受保护pending-exception路径已有SSA/LLVM focused coverage和Windows real-Zig host child-JVM differential；六目标交叉构建只提供结构性产物证据，不代表Linux/macOS或Windows Arm64上的JVM运行时已经执行验证。

## Tier 4: JDK Runtime Interop

目标：常见 Java library通过 JVM-hosted intrinsic、runtime helper与普通 JNI/JVM dispatch可用。

功能范围：

- String、StringBuilder、ArrayList、HashMap、Arrays、Collections、Optional、Objects、Math。
- `System.arraycopy`、`Object.getClass` 等 intrinsic/helper。
- direct lowering / runtime helper / validated JVM dispatch / skipped policy。
- Class/Method/Field 的静态可解析 metadata subset。

已接实 subset：

- String/StringBuilder/StringConcat、System.arraycopy、Math、boxing、Objects、`Thread.sleep(J)V`，以及 `Object.getClass()` / `Class.getClassLoader()` 的env-backed JNI helper。`Object.getClass()` 对null receiver显式产生 `NullPointerException`，非null receiver通过JNI `GetObjectClass` 返回JVM-managed `Class` object。
- common LambdaMetafactory、LDC MethodHandle direct target 与受限 adapter dispatch。
- validated descriptor matrix内的 collection/formatter/Throwable/Thread/JDK calls可从 native implementation经 JNI dispatch执行；当前额外覆盖class-relative resource stream、InputStream close/read、ByteBuffer parse、`Arrays.fill(byte[], byte)`、Throwable suppression与`privateLookupIn`/hidden-class lookup链。精确same-block unique-use `ByteBuffer.allocate(4).putInt(i).array()`会进一步融合为native frame helper，JNI创建结果`byte[]`，不建立native ByteBuffer/object model。

边界：

- native code不读取 JDK collection/Throwable/Thread object layout，不伪造 Java array、Throwable stack trace 或 thread scheduler。
- 超出 validated helper/dispatch matrix时，整个 selected caller为 `skipped`；原 method body留在原 class，不另存第二份。
- 完整 MethodHandle interpreter、完整 altMetafactory semantics和复杂 lambda capture仍是后续 native support任务。

Runtime Loader 只承载 native library选择、SHA-256校验、加载与注册；field internalization实际包含 reference/array slot时才按需加入 per-defining-Class `ClassValue<Object[]>` sidecar。Loader没有 class-definition或 embedded-bytecode decode API，也不生成 companion/nested runtime class。

测试要求：

- intrinsic/helper/dispatch runtime parity。
- unsupported JDK caller skipped reason与 preservation。
- Loader最小 API surface和双 ClassLoader isolation。

## Tier 5: 静态高级特性

目标：写死在代码里的动态特性可以通过静态 classpath analysis或 runtime metadata提前处理。

功能范围：

- 常量 reflection目标、method/constructor/field invoke。
- MethodHandle/VarHandle常见静态形态。
- JNI declaration、`RegisterNatives`、reference lifetime。
- ConstantDynamic与 invokedynamic扩展 subset。
- Unsafe field/array token、CAS、`allocateInstance` 的严格 helper边界。

当前状态：

- Runtime metadata index/dump覆盖 Signature、annotations、record、nest/inner、bridge/synthetic、class object/init facts。
- 常量 reflection与 bounded `setAccessible(true)`已有 helper E2E；动态 reflection和 scan APIs只在 validated descriptor bridge内保持 `nativeLowered`。
- MethodHandle common metadata/direct/adapter shape可走 native helper；unsupported bootstrap/adapter/capture shape的 selected caller为 `skipped`。
- Unsafe/VarHandle bounded subset使用 metadata token和 JNI helper，不使用 native object address；raw-memory或更宽动态 shape为 `skipped`。

Release evidence：

- support/opcode matrix用 `NATIVE_LOWERED`、`HELPER_BACKED` 与 `SKIPPED` evidence；前两者都映射到 method outcome `nativeLowered`。
- `reports/support-matrix.json` 与 `reports/opcode-support-matrix.json` 保存上述 machine-readable coverage；签名成功证据使用 `SIGNATURE_RESIGNED`。
- artifact audit校验native implementation closure、registered入口的registration、internal-only入口的declaration/registration/reference absence、skipped-body preservation、Loader API surface、metadata/hash/export/PDB、sensitive plaintext，以及 generated C/native/JAR没有 selected method bytecode副本。
- release suite覆盖 native/helper paths、精确 skipped reasons、skipped confirmation的 Y/N/EOF、签名/target/audit failures与 realistic samples。
- 六目标结构性交叉产物与 non-host OS/JVM runtime E2E分开记录。

## Skipped-Method Build Gate

Default build在 final implementation plan确定后、创建 Zig workspace或调用 Zig前：

1. stderr按稳定顺序逐条打印 skipped method identity、reason code和 reason。
2. warning明确说明这些方法不会 native lowered，原 Java bytecode会保留在输出 JAR。
3. 提示 `continue? (Y/N)`；只有显式 `Y`继续，`N`或 EOF终止。

Piped `Y`是正式自动化入口，非 TTY/CI不能绕过。`--validate`与`--dry-run`不读取 stdin，也不形成 final skipped set；dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 与 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。

当前仍需保持稳定、用于定位待实现 native coverage 的 reason code包括：

- exception/interface：`UNSUPPORTED_DEFAULT_INTERFACE_SUPER`、`UNSUPPORTED_JVM_EXCEPTION_FLOW`、`UNSUPPORTED_EXCEPTION_STATE_MERGE`、`UNSUPPORTED_MONITOR_FINALLY_INTERACTION`；
- reflection/Unsafe：`REFLECTION_DYNAMIC_UNSUPPORTED`、`UNSAFE_RAW_MEMORY_UNSUPPORTED`、`VAR_HANDLE_DYNAMIC_UNSUPPORTED`；
- MethodHandle/lambda：`METHOD_HANDLE_PERMUTE_UNSUPPORTED`、`METHOD_HANDLE_FILTER_UNSUPPORTED`、`METHOD_HANDLE_FOLD_UNSUPPORTED`、`METHOD_HANDLE_COLLECTOR_UNSUPPORTED`、`ALT_METAFACTORY_UNSUPPORTED`；
- JVM/JDK boundaries：`JVM_HELPER_UNSUPPORTED`、`THREAD_HELPER_UNSUPPORTED`、`WAIT_NOTIFY_UNSUPPORTED`；
- toolchain：`ZIG_TARGET_UNBUILDABLE`。

这些 reason code只描述尚未实现的 native coverage；对应 selected caller的最终状态一律是 `skipped`，不表示存在第二种执行实现。

## 使用方式

- 新功能先标注所属 tier，并同时定义 direct/helper/native plan、skipped reason和测试落点。
- 一个 tier 的功能只有核心测试稳定后，才可在 README/release note宣称支持。
- 跨 tier功能按最高风险部分归类。
- 遇到不确定语义时把整个 selected method标记为 `skipped`，保留原 Java body并给稳定 reason。
- 后续工作的主线是按 reason code逐项扩大 native/helper coverage，持续减少 skipped methods。
